/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.orc.writer;

import com.facebook.presto.array.IntBigArray;
import com.facebook.presto.orc.DictionaryCompressionOptimizer.DictionaryColumn;
import com.facebook.presto.orc.checkpoint.BooleanStreamCheckpoint;
import com.facebook.presto.orc.checkpoint.LongStreamCheckpoint;
import com.facebook.presto.orc.metadata.ColumnEncoding;
import com.facebook.presto.orc.metadata.CompressionKind;
import com.facebook.presto.orc.metadata.MetadataWriter;
import com.facebook.presto.orc.metadata.RowGroupIndex;
import com.facebook.presto.orc.metadata.Stream;
import com.facebook.presto.orc.metadata.Stream.StreamKind;
import com.facebook.presto.orc.metadata.statistics.ColumnStatistics;
import com.facebook.presto.orc.metadata.statistics.StringStatisticsBuilder;
import com.facebook.presto.orc.stream.ByteArrayOutputStream;
import com.facebook.presto.orc.stream.LongOutputStream;
import com.facebook.presto.orc.stream.LongOutputStreamV1;
import com.facebook.presto.orc.stream.LongOutputStreamV2;
import com.facebook.presto.orc.stream.PresentOutputStream;
import com.facebook.presto.spi.block.Block;
import com.facebook.presto.spi.block.DictionaryBlock;
import com.facebook.presto.spi.type.Type;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.airlift.slice.Slice;
import io.airlift.slice.SliceOutput;
import it.unimi.dsi.fastutil.ints.AbstractIntComparator;
import it.unimi.dsi.fastutil.ints.IntArrays;
import org.openjdk.jol.info.ClassLayout;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.facebook.presto.orc.DictionaryCompressionOptimizer.estimateIndexBytesPerValue;
import static com.facebook.presto.orc.metadata.ColumnEncoding.ColumnEncodingKind.DICTIONARY;
import static com.facebook.presto.orc.metadata.ColumnEncoding.ColumnEncodingKind.DICTIONARY_V2;
import static com.facebook.presto.orc.metadata.CompressionKind.NONE;
import static com.facebook.presto.orc.metadata.Stream.StreamKind.DATA;
import static com.facebook.presto.orc.stream.LongOutputStream.createLengthOutputStream;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.Math.toIntExact;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;

public class SliceDictionaryColumnWriter
        implements ColumnWriter, DictionaryColumn
{
    private static final int INSTANCE_SIZE = ClassLayout.parseClass(SliceDictionaryColumnWriter.class).instanceSize();
    private final int column;
    private final Type type;
    private final CompressionKind compression;
    private final int bufferSize;
    private final boolean isDwrf;

    private final LongOutputStream dataStream;
    private final PresentOutputStream presentStream;
    private final ByteArrayOutputStream dictionaryDataStream;
    private final LongOutputStream dictionaryLengthStream;

    private final DictionaryBuilder dictionary = new DictionaryBuilder(10000);

    private final List<DictionaryRowGroup> rowGroups = new ArrayList<>();

    private IntBigArray values;
    private int valueCount;
    private StringStatisticsBuilder statisticsBuilder = new StringStatisticsBuilder();

    private long rawBytes;

    private boolean closed;
    private boolean inRowGroup;
    private ColumnEncoding columnEncoding;

    private boolean directEncoded;
    private SliceDirectColumnWriter directColumnWriter;

    public SliceDictionaryColumnWriter(int column, Type type, CompressionKind compression, int bufferSize, boolean isDwrf)
    {
        checkArgument(column >= 0, "column is negative");
        this.column = column;
        this.type = requireNonNull(type, "type is null");
        this.compression = requireNonNull(compression, "compression is null");
        this.bufferSize = bufferSize;
        this.isDwrf = isDwrf;
        LongOutputStream result;
        if (isDwrf) {
            result = new LongOutputStreamV1(compression, bufferSize, false, DATA);
        }
        else {
            result = new LongOutputStreamV2(compression, bufferSize, false, DATA);
        }
        this.dataStream = result;
        this.presentStream = new PresentOutputStream(compression, bufferSize);
        this.dictionaryDataStream = new ByteArrayOutputStream(compression, bufferSize, StreamKind.DICTIONARY_DATA);
        this.dictionaryLengthStream = createLengthOutputStream(compression, bufferSize, isDwrf);
        values = new IntBigArray();
    }

    @Override
    public long getRawBytes()
    {
        checkState(!directEncoded);
        return rawBytes;
    }

    @Override
    public int getDictionaryBytes()
    {
        checkState(!directEncoded);
        return toIntExact(dictionary.getSizeInBytes());
    }

    @Override
    public int getValueCount()
    {
        return valueCount;
    }

    @Override
    public int getNonNullValueCount()
    {
        return toIntExact(statisticsBuilder.getNonNullValueCount());
    }

    @Override
    public int getDictionaryEntries()
    {
        return dictionary.getEntryCount();
    }

    @Override
    public void convertToDirect()
    {
        checkState(!closed);
        checkState(!directEncoded);
        if (directColumnWriter == null) {
            directColumnWriter = new SliceDirectColumnWriter(column, type, compression, bufferSize, isDwrf, StringStatisticsBuilder::new);
        }

        Block dictionaryValues = dictionary.getElementBlock();
        for (DictionaryRowGroup rowGroup : rowGroups) {
            directColumnWriter.beginRowGroup();
            // todo we should be able to pass the stats down to avoid recalculating min and max
            writeDictionaryRowGroup(dictionaryValues, rowGroup.getValueCount(), rowGroup.getDictionaryIndexes());
            directColumnWriter.finishRowGroup();
        }
        if (inRowGroup) {
            directColumnWriter.beginRowGroup();
            writeDictionaryRowGroup(dictionaryValues, valueCount, values);
        }
        else {
            checkState(valueCount == 0);
        }

        rowGroups.clear();
        rawBytes = 0;
        valueCount = 0;
        statisticsBuilder = new StringStatisticsBuilder();

        directEncoded = true;
    }

    private void writeDictionaryRowGroup(Block dictionary, int valueCount, IntBigArray dictionaryIndexes)
    {
        int[][] segments = dictionaryIndexes.getSegments();
        for (int i = 0; valueCount > 0 && i < segments.length; i++) {
            int[] segment = segments[i];
            int positionCount = Math.min(valueCount, segment.length);
            DictionaryBlock dictionaryBlock = new DictionaryBlock(positionCount, dictionary, segment);
            directColumnWriter.writeBlock(dictionaryBlock);
            valueCount -= positionCount;
        }
        checkState(valueCount == 0);
    }

    @Override
    public Map<Integer, ColumnEncoding> getColumnEncodings()
    {
        checkState(closed);
        if (directEncoded) {
            return directColumnWriter.getColumnEncodings();
        }
        return ImmutableMap.of(column, columnEncoding);
    }

    @Override
    public void beginRowGroup()
    {
        checkState(!inRowGroup);
        inRowGroup = true;

        if (directEncoded) {
            directColumnWriter.beginRowGroup();
        }
    }

    @Override
    public void writeBlock(Block block)
    {
        checkState(!closed);
        checkArgument(block.getPositionCount() > 0, "Block is empty");

        if (directEncoded) {
            directColumnWriter.writeBlock(block);
            return;
        }

        // record values
        values.ensureCapacity(valueCount + block.getPositionCount());
        for (int position = 0; position < block.getPositionCount(); position++) {
            int index = dictionary.putIfAbsent(block, position);
            values.set(valueCount, index);
            valueCount++;

            if (!block.isNull(position)) {
                // todo min/max statistics only need to be updated if value was not already in the dictionary, but non-null count does
                statisticsBuilder.addValue(type.getSlice(block, position));

                rawBytes += block.getSliceLength(position);
            }
        }
    }

    @Override
    public void finishRowGroup()
    {
        checkState(!closed);
        checkState(inRowGroup);
        inRowGroup = false;

        if (directEncoded) {
            directColumnWriter.finishRowGroup();
            return;
        }

        rowGroups.add(new DictionaryRowGroup(values, valueCount, statisticsBuilder.buildColumnStatistics()));
        valueCount = 0;
        statisticsBuilder = new StringStatisticsBuilder();
        values = new IntBigArray();
    }

    @Override
    public void close()
    {
        checkState(!closed);
        checkState(!inRowGroup);
        closed = true;
        if (directEncoded) {
            directColumnWriter.close();
        }
        else {
            bufferOutputData();
        }
    }

    @Override
    public Map<Integer, ColumnStatistics> getColumnStripeStatistics()
    {
        checkState(closed);
        if (directEncoded) {
            return directColumnWriter.getColumnStripeStatistics();
        }

        return ImmutableMap.of(column, ColumnStatistics.mergeColumnStatistics(rowGroups.stream()
                .map(DictionaryRowGroup::getColumnStatistics)
                .collect(toList())));
    }

    private void bufferOutputData()
    {
        checkState(closed);
        checkState(!directEncoded);

        Block dictionaryElements = dictionary.getElementBlock();

        // write dictionary in sorted order
        int[] sortedDictionaryIndexes = getSortedDictionaryNullsLast(dictionaryElements);
        for (int sortedDictionaryIndex : sortedDictionaryIndexes) {
            if (!dictionaryElements.isNull(sortedDictionaryIndex)) {
                int length = dictionaryElements.getSliceLength(sortedDictionaryIndex);
                dictionaryLengthStream.writeLong(length);
                Slice value = dictionaryElements.getSlice(sortedDictionaryIndex, 0, length);
                dictionaryDataStream.writeSlice(value);
            }
        }
        columnEncoding = new ColumnEncoding(isDwrf ? DICTIONARY : DICTIONARY_V2, dictionaryElements.getPositionCount() - 1);

        // build index from original dictionary index to new sorted position
        int[] originalDictionaryToSortedIndex = new int[sortedDictionaryIndexes.length];
        for (int sortOrdinal = 0; sortOrdinal < sortedDictionaryIndexes.length; sortOrdinal++) {
            int dictionaryIndex = sortedDictionaryIndexes[sortOrdinal];
            originalDictionaryToSortedIndex[dictionaryIndex] = sortOrdinal;
        }

        if (!rowGroups.isEmpty()) {
            presentStream.recordCheckpoint();
            dataStream.recordCheckpoint();
        }
        for (DictionaryRowGroup rowGroup : rowGroups) {
            IntBigArray dictionaryIndexes = rowGroup.getDictionaryIndexes();
            for (int position = 0; position < rowGroup.getValueCount(); position++) {
                presentStream.writeBoolean(dictionaryIndexes.get(position) != 0);
            }
            for (int position = 0; position < rowGroup.getValueCount(); position++) {
                int originalDictionaryIndex = dictionaryIndexes.get(position);
                // index zero in original dictionary is reserved for null
                if (originalDictionaryIndex != 0) {
                    int sortedIndex = originalDictionaryToSortedIndex[originalDictionaryIndex];
                    if (sortedIndex < 0) {
                        throw new IllegalArgumentException();
                    }
                    dataStream.writeLong(sortedIndex);
                }
            }
            presentStream.recordCheckpoint();
            dataStream.recordCheckpoint();
        }

        // free the dictionary memory
        dictionary.clear();
        dictionaryDataStream.close();
        dictionaryLengthStream.close();

        dataStream.close();
        presentStream.close();
    }

    private static int[] getSortedDictionaryNullsLast(Block elementBlock)
    {
        int[] sortedPositions = new int[elementBlock.getPositionCount()];
        for (int i = 0; i < sortedPositions.length; i++) {
            sortedPositions[i] = i;
        }

        IntArrays.quickSort(sortedPositions, 0, sortedPositions.length, new AbstractIntComparator() {
            @Override
            public int compare(int left, int right)
            {
                boolean nullLeft = elementBlock.isNull(left);
                boolean nullRight = elementBlock.isNull(right);
                if (nullLeft && nullRight) {
                    return 0;
                }
                if (nullLeft) {
                    return 1;
                }
                if (nullRight) {
                    return -1;
                }

                return elementBlock.compareTo(
                        left,
                        0,
                        elementBlock.getSliceLength(left),
                        elementBlock,
                        right,
                        0,
                        elementBlock.getSliceLength(right));
            }
        });

        return sortedPositions;
    }

    @Override
    public List<Stream> writeIndexStreams(SliceOutput outputStream, MetadataWriter metadataWriter)
            throws IOException
    {
        checkState(closed);

        if (directEncoded) {
            return directColumnWriter.writeIndexStreams(outputStream, metadataWriter);
        }

        ImmutableList.Builder<RowGroupIndex> rowGroupIndexes = ImmutableList.builder();

        List<LongStreamCheckpoint> dataCheckpoints = dataStream.getCheckpoints();
        Optional<List<BooleanStreamCheckpoint>> presentCheckpoints = presentStream.getCheckpoints();
        for (int i = 0; i < rowGroups.size(); i++) {
            int groupId = i;
            ColumnStatistics columnStatistics = rowGroups.get(groupId).getColumnStatistics();
            LongStreamCheckpoint dataCheckpoint = dataCheckpoints.get(groupId);
            Optional<BooleanStreamCheckpoint> presentCheckpoint = presentCheckpoints.map(checkpoints -> checkpoints.get(groupId));
            List<Integer> positions = createSliceColumnPositionList(compression != NONE, dataCheckpoint, presentCheckpoint);
            rowGroupIndexes.add(new RowGroupIndex(positions, columnStatistics));
        }

        int length = metadataWriter.writeRowIndexes(outputStream, rowGroupIndexes.build());
        return ImmutableList.of(new Stream(column, StreamKind.ROW_INDEX, length, false));
    }

    private static List<Integer> createSliceColumnPositionList(
            boolean compressed,
            LongStreamCheckpoint dataCheckpoint,
            Optional<BooleanStreamCheckpoint> presentCheckpoint)
    {
        ImmutableList.Builder<Integer> positionList = ImmutableList.builder();
        presentCheckpoint.ifPresent(booleanStreamCheckpoint -> positionList.addAll(booleanStreamCheckpoint.toPositionList(compressed)));
        positionList.addAll(dataCheckpoint.toPositionList(compressed));
        return positionList.build();
    }

    @Override
    public List<Stream> writeDataStreams(SliceOutput outputStream)
            throws IOException
    {
        checkState(closed);

        if (directEncoded) {
            return directColumnWriter.writeDataStreams(outputStream);
        }

        // actually write data
        ImmutableList.Builder<Stream> dataStreams = ImmutableList.builder();

        presentStream.writeDataStreams(column, outputStream).ifPresent(dataStreams::add);
        dataStream.writeDataStreams(column, outputStream).ifPresent(dataStreams::add);
        dictionaryLengthStream.writeDataStreams(column, outputStream).ifPresent(dataStreams::add);
        dictionaryDataStream.writeDataStreams(column, outputStream).ifPresent(dataStreams::add);
        return dataStreams.build();
    }

    @Override
    public long getBufferedBytes()
    {
        checkState(!closed);
        if (directEncoded) {
            return directColumnWriter.getBufferedBytes();
        }
        // for dictionary columns we report the data we expect to write to the output stream
        int indexBytes = estimateIndexBytesPerValue(dictionary.getEntryCount()) * getNonNullValueCount();
        return indexBytes + getDictionaryBytes();
    }

    @Override
    public long getRetainedBytes()
    {
        // NOTE: we do not include stats because they should be small and it would be annoying to calculate the size
        return INSTANCE_SIZE +
                values.sizeOf() +
                dataStream.getRetainedBytes() +
                presentStream.getRetainedBytes() +
                dictionaryDataStream.getRetainedBytes() +
                dictionaryLengthStream.getRetainedBytes() +
                dictionary.getRetainedSizeInBytes() +
                (directColumnWriter == null ? 0 : directColumnWriter.getRetainedBytes());
    }

    @Override
    public void reset()
    {
        checkState(closed);
        closed = false;
        dataStream.reset();
        presentStream.reset();
        dictionaryDataStream.reset();
        dictionaryLengthStream.reset();
        rowGroups.clear();
        valueCount = 0;
        statisticsBuilder = new StringStatisticsBuilder();
        columnEncoding = null;
        dictionary.clear();
        rawBytes = 0;
        if (directEncoded) {
            directEncoded = false;
            directColumnWriter.reset();
        }
    }

    private static class DictionaryRowGroup
    {
        private final IntBigArray dictionaryIndexes;
        private final int valueCount;
        private final ColumnStatistics columnStatistics;

        public DictionaryRowGroup(IntBigArray dictionaryIndexes, int valueCount, ColumnStatistics columnStatistics)
        {
            requireNonNull(dictionaryIndexes, "dictionaryIndexes is null");
            checkArgument(valueCount >= 0, "valueCount is negative");
            requireNonNull(columnStatistics, "columnStatistics is null");

            this.dictionaryIndexes = dictionaryIndexes;
            this.valueCount = valueCount;
            this.columnStatistics = columnStatistics;
        }

        public IntBigArray getDictionaryIndexes()
        {
            return dictionaryIndexes;
        }

        public int getValueCount()
        {
            return valueCount;
        }

        public ColumnStatistics getColumnStatistics()
        {
            return columnStatistics;
        }
    }
}
