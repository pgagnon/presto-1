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
package io.prestosql.orc;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import io.airlift.slice.Slice;
import io.prestosql.orc.metadata.ColumnMetadata;
import io.prestosql.orc.metadata.OrcColumnId;
import io.prestosql.orc.metadata.statistics.BloomFilter;
import io.prestosql.orc.metadata.statistics.BooleanStatistics;
import io.prestosql.orc.metadata.statistics.ColumnStatistics;
import io.prestosql.orc.metadata.statistics.RangeStatistics;
import io.prestosql.spi.predicate.Domain;
import io.prestosql.spi.predicate.Range;
import io.prestosql.spi.predicate.ValueSet;
import io.prestosql.spi.type.CharType;
import io.prestosql.spi.type.DateType;
import io.prestosql.spi.type.DecimalType;
import io.prestosql.spi.type.LongTimestamp;
import io.prestosql.spi.type.LongTimestampWithTimeZone;
import io.prestosql.spi.type.TimeType;
import io.prestosql.spi.type.Timestamps;
import io.prestosql.spi.type.Type;
import io.prestosql.spi.type.VarbinaryType;
import io.prestosql.spi.type.VarcharType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static com.google.common.base.MoreObjects.toStringHelper;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.BooleanType.BOOLEAN;
import static io.prestosql.spi.type.Chars.truncateToLengthAndTrimSpaces;
import static io.prestosql.spi.type.DateTimeEncoding.packDateTimeWithZone;
import static io.prestosql.spi.type.DateTimeEncoding.unpackMillisUtc;
import static io.prestosql.spi.type.DateType.DATE;
import static io.prestosql.spi.type.Decimals.encodeUnscaledValue;
import static io.prestosql.spi.type.Decimals.isLongDecimal;
import static io.prestosql.spi.type.Decimals.isShortDecimal;
import static io.prestosql.spi.type.Decimals.rescale;
import static io.prestosql.spi.type.DoubleType.DOUBLE;
import static io.prestosql.spi.type.IntegerType.INTEGER;
import static io.prestosql.spi.type.RealType.REAL;
import static io.prestosql.spi.type.SmallintType.SMALLINT;
import static io.prestosql.spi.type.TimeZoneKey.UTC_KEY;
import static io.prestosql.spi.type.TimestampType.TIMESTAMP_MICROS;
import static io.prestosql.spi.type.TimestampType.TIMESTAMP_MILLIS;
import static io.prestosql.spi.type.TimestampType.TIMESTAMP_NANOS;
import static io.prestosql.spi.type.TimestampWithTimeZoneType.TIMESTAMP_TZ_MICROS;
import static io.prestosql.spi.type.TimestampWithTimeZoneType.TIMESTAMP_TZ_MILLIS;
import static io.prestosql.spi.type.TimestampWithTimeZoneType.TIMESTAMP_TZ_NANOS;
import static io.prestosql.spi.type.Timestamps.MICROSECONDS_PER_MILLISECOND;
import static io.prestosql.spi.type.TinyintType.TINYINT;
import static java.lang.Float.floatToRawIntBits;
import static java.lang.Float.intBitsToFloat;
import static java.lang.Math.floorDiv;
import static java.util.Objects.requireNonNull;

public class TupleDomainOrcPredicate
        implements OrcPredicate
{
    private final List<ColumnDomain> columnDomains;
    private final boolean orcBloomFiltersEnabled;

    public static TupleDomainOrcPredicateBuilder builder()
    {
        return new TupleDomainOrcPredicateBuilder();
    }

    private TupleDomainOrcPredicate(List<ColumnDomain> columnDomains, boolean orcBloomFiltersEnabled)
    {
        this.columnDomains = ImmutableList.copyOf(requireNonNull(columnDomains, "columnDomains is null"));
        this.orcBloomFiltersEnabled = orcBloomFiltersEnabled;
    }

    @Override
    public boolean matches(long numberOfRows, ColumnMetadata<ColumnStatistics> allColumnStatistics)
    {
        for (ColumnDomain column : columnDomains) {
            ColumnStatistics columnStatistics = allColumnStatistics.get(column.getColumnId());
            if (columnStatistics == null) {
                // no statistics for this column, so we can't exclude this section
                continue;
            }

            if (!columnOverlaps(column.getDomain(), numberOfRows, columnStatistics)) {
                return false;
            }
        }

        // this section was not excluded
        return true;
    }

    private boolean columnOverlaps(Domain predicateDomain, long numberOfRows, ColumnStatistics columnStatistics)
    {
        Domain stripeDomain = getDomain(predicateDomain.getType(), numberOfRows, columnStatistics);
        if (!stripeDomain.overlaps(predicateDomain)) {
            // there is no overlap between the predicate and this column
            return false;
        }

        // if bloom filters are not enabled, we cannot restrict the range overlap
        if (!orcBloomFiltersEnabled) {
            return true;
        }

        // if there an overlap in null values, the bloom filter cannot eliminate the overlap
        if (predicateDomain.isNullAllowed() && stripeDomain.isNullAllowed()) {
            return true;
        }

        // extract the discrete values from the predicate
        Optional<Collection<Object>> discreteValues = extractDiscreteValues(predicateDomain.getValues());
        if (discreteValues.isEmpty()) {
            // values are not discrete, so we can't exclude this section
            return true;
        }

        BloomFilter bloomFilter = columnStatistics.getBloomFilter();
        if (bloomFilter == null) {
            // no bloom filter so we can't exclude this section
            return true;
        }

        // if none of the discrete predicate values are found in the bloom filter, there is no overlap and the section should be skipped
        return discreteValues.get().stream().anyMatch(value -> checkInBloomFilter(bloomFilter, value, stripeDomain.getType()));
    }

    @VisibleForTesting
    public static Optional<Collection<Object>> extractDiscreteValues(ValueSet valueSet)
    {
        return valueSet.getValuesProcessor().transform(
                ranges -> {
                    ImmutableList.Builder<Object> discreteValues = ImmutableList.builder();
                    for (Range range : ranges.getOrderedRanges()) {
                        if (!range.isSingleValue()) {
                            return Optional.empty();
                        }
                        discreteValues.add(range.getSingleValue());
                    }
                    return Optional.of(discreteValues.build());
                },
                discreteValues -> Optional.of(discreteValues.getValues()),
                allOrNone -> allOrNone.isAll() ? Optional.empty() : Optional.of(ImmutableList.of()));
    }

    // checks whether a value part of the effective predicate is likely to be part of this bloom filter
    @VisibleForTesting
    public static boolean checkInBloomFilter(BloomFilter bloomFilter, Object predicateValue, Type sqlType)
    {
        if (sqlType == TINYINT || sqlType == SMALLINT || sqlType == INTEGER || sqlType == BIGINT || sqlType == DATE) {
            return bloomFilter.testLong(((Number) predicateValue).longValue());
        }

        if (sqlType == DOUBLE) {
            return bloomFilter.testDouble((Double) predicateValue);
        }

        if (sqlType == REAL) {
            return bloomFilter.testFloat(intBitsToFloat(((Number) predicateValue).intValue()));
        }

        if (sqlType instanceof VarcharType || sqlType instanceof VarbinaryType) {
            return bloomFilter.testSlice(((Slice) predicateValue));
        }

        // Bloom filters for timestamps are truncated to millis
        if (sqlType.equals(TIMESTAMP_MILLIS)) {
            return bloomFilter.testLong(((Number) predicateValue).longValue());
        }
        if (sqlType.equals(TIMESTAMP_MICROS)) {
            return bloomFilter.testLong(floorDiv(((Number) predicateValue).longValue(), MICROSECONDS_PER_MILLISECOND));
        }
        if (sqlType.equals(TIMESTAMP_NANOS)) {
            return bloomFilter.testLong(floorDiv(((LongTimestamp) predicateValue).getEpochMicros(), MICROSECONDS_PER_MILLISECOND));
        }
        if (sqlType.equals(TIMESTAMP_TZ_MILLIS)) {
            return bloomFilter.testLong(unpackMillisUtc(((Number) predicateValue).longValue()));
        }
        if (sqlType.equals(TIMESTAMP_TZ_MICROS) || sqlType.equals(TIMESTAMP_TZ_NANOS)) {
            return bloomFilter.testLong(((LongTimestampWithTimeZone) predicateValue).getEpochMillis());
        }

        // todo support DECIMAL, and CHAR
        return true;
    }

    @VisibleForTesting
    public static Domain getDomain(Type type, long rowCount, ColumnStatistics columnStatistics)
    {
        if (rowCount == 0) {
            return Domain.none(type);
        }

        if (columnStatistics == null) {
            return Domain.all(type);
        }

        if (columnStatistics.hasNumberOfValues() && columnStatistics.getNumberOfValues() == 0) {
            return Domain.onlyNull(type);
        }

        boolean hasNullValue = columnStatistics.getNumberOfValues() != rowCount;

        if (type instanceof TimeType && columnStatistics.getIntegerStatistics() != null) {
            // This is the representation of TIME used by Iceberg
            return createDomain(type, hasNullValue, columnStatistics.getIntegerStatistics(), value -> ((long) value) * Timestamps.PICOSECONDS_PER_MICROSECOND);
        }
        if (type.getJavaType() == boolean.class && columnStatistics.getBooleanStatistics() != null) {
            BooleanStatistics booleanStatistics = columnStatistics.getBooleanStatistics();

            boolean hasTrueValues = (booleanStatistics.getTrueValueCount() != 0);
            boolean hasFalseValues = (columnStatistics.getNumberOfValues() != booleanStatistics.getTrueValueCount());
            if (hasTrueValues && hasFalseValues) {
                return Domain.all(BOOLEAN);
            }
            if (hasTrueValues) {
                return Domain.create(ValueSet.of(BOOLEAN, true), hasNullValue);
            }
            if (hasFalseValues) {
                return Domain.create(ValueSet.of(BOOLEAN, false), hasNullValue);
            }
        }
        else if (isShortDecimal(type) && columnStatistics.getDecimalStatistics() != null) {
            return createDomain(type, hasNullValue, columnStatistics.getDecimalStatistics(), value -> rescale(value, (DecimalType) type).unscaledValue().longValue());
        }
        else if (isLongDecimal(type) && columnStatistics.getDecimalStatistics() != null) {
            return createDomain(type, hasNullValue, columnStatistics.getDecimalStatistics(), value -> encodeUnscaledValue(rescale(value, (DecimalType) type).unscaledValue()));
        }
        else if (type instanceof CharType && columnStatistics.getStringStatistics() != null) {
            return createDomain(type, hasNullValue, columnStatistics.getStringStatistics(), value -> truncateToLengthAndTrimSpaces(value, type));
        }
        else if (type instanceof VarcharType && columnStatistics.getStringStatistics() != null) {
            return createDomain(type, hasNullValue, columnStatistics.getStringStatistics());
        }
        else if (type instanceof DateType && columnStatistics.getDateStatistics() != null) {
            return createDomain(type, hasNullValue, columnStatistics.getDateStatistics(), value -> (long) value);
        }
        else if ((type.equals(TIMESTAMP_MILLIS) || type.equals(TIMESTAMP_MICROS)) && columnStatistics.getTimestampStatistics() != null) {
            // ORC timestamp statistics are truncated to millisecond precision, regardless of the precision of the actual data column.
            // Since that can cause some column values to fall outside the stats range, here we are creating a tuple domain predicate
            // that ensures inclusion of all values.  Note that we are adding a full millisecond to account for the fact that Presto rounds
            // timestamps. For example, the stats for timestamp 2020-09-22 12:34:56.678910 are truncated to 2020-09-22 12:34:56.678.
            // If Presto is using millisecond precision, the timestamp gets rounded to the next millisecond (2020-09-22 12:34:56.679), so the
            // upper bound of the domain we create must be adjusted accordingly, to includes the rounded timestamp.
            return createDomain(
                    type,
                    hasNullValue,
                    columnStatistics.getTimestampStatistics(),
                    min -> min * MICROSECONDS_PER_MILLISECOND,
                    max -> (max + 1) * MICROSECONDS_PER_MILLISECOND);
        }
        else if (type.equals(TIMESTAMP_NANOS) && columnStatistics.getTimestampStatistics() != null) {
            return createDomain(
                    type,
                    hasNullValue,
                    columnStatistics.getTimestampStatistics(),
                    min -> new LongTimestamp(min * MICROSECONDS_PER_MILLISECOND, 0),
                    max -> new LongTimestamp((max + 1) * MICROSECONDS_PER_MILLISECOND, 0));
        }
        else if (type.equals(TIMESTAMP_TZ_MILLIS) && columnStatistics.getTimestampStatistics() != null) {
            return createDomain(type, hasNullValue, columnStatistics.getTimestampStatistics(), value -> packDateTimeWithZone(value, UTC_KEY));
        }
        else if (type.equals(TIMESTAMP_TZ_MICROS) && (columnStatistics.getTimestampStatistics() != null)) {
            return createDomain(
                    type,
                    hasNullValue,
                    columnStatistics.getTimestampStatistics(),
                    min -> LongTimestampWithTimeZone.fromEpochMillisAndFraction(min, 0, UTC_KEY),
                    max -> LongTimestampWithTimeZone.fromEpochMillisAndFraction(max, 999_000_000, UTC_KEY));
        }
        else if (type.equals(TIMESTAMP_TZ_NANOS) && columnStatistics.getTimestampStatistics() != null) {
            return createDomain(
                    type,
                    hasNullValue,
                    columnStatistics.getTimestampStatistics(),
                    min -> LongTimestampWithTimeZone.fromEpochMillisAndFraction(min, 0, UTC_KEY),
                    max -> LongTimestampWithTimeZone.fromEpochMillisAndFraction(max, 999_999_000, UTC_KEY));
        }
        else if (type.getJavaType() == long.class && columnStatistics.getIntegerStatistics() != null) {
            return createDomain(type, hasNullValue, columnStatistics.getIntegerStatistics());
        }
        else if (type.getJavaType() == double.class && columnStatistics.getDoubleStatistics() != null) {
            return createDomain(type, hasNullValue, columnStatistics.getDoubleStatistics());
        }
        else if (REAL.equals(type) && columnStatistics.getDoubleStatistics() != null) {
            return createDomain(type, hasNullValue, columnStatistics.getDoubleStatistics(), value -> (long) floatToRawIntBits(value.floatValue()));
        }
        return Domain.create(ValueSet.all(type), hasNullValue);
    }

    private static <T extends Comparable<T>> Domain createDomain(Type type, boolean hasNullValue, RangeStatistics<T> rangeStatistics)
    {
        return createDomain(type, hasNullValue, rangeStatistics, value -> value);
    }

    private static <F, T extends Comparable<T>> Domain createDomain(Type type, boolean hasNullValue, RangeStatistics<F> rangeStatistics, Function<F, T> function)
    {
        return createDomain(type, hasNullValue, rangeStatistics, function, function);
    }

    private static <F, T extends Comparable<T>> Domain createDomain(Type type, boolean hasNullValue, RangeStatistics<F> rangeStatistics, Function<F, T> minFunction, Function<F, T> maxFunction)
    {
        F min = rangeStatistics.getMin();
        F max = rangeStatistics.getMax();

        if (min != null && max != null) {
            return Domain.create(ValueSet.ofRanges(Range.range(type, minFunction.apply(min), true, maxFunction.apply(max), true)), hasNullValue);
        }
        if (max != null) {
            return Domain.create(ValueSet.ofRanges(Range.lessThanOrEqual(type, maxFunction.apply(max))), hasNullValue);
        }
        if (min != null) {
            return Domain.create(ValueSet.ofRanges(Range.greaterThanOrEqual(type, minFunction.apply(min))), hasNullValue);
        }
        return Domain.create(ValueSet.all(type), hasNullValue);
    }

    public static class TupleDomainOrcPredicateBuilder
    {
        private final List<ColumnDomain> columns = new ArrayList<>();
        private boolean bloomFiltersEnabled;

        public TupleDomainOrcPredicateBuilder addColumn(OrcColumnId columnId, Domain domain)
        {
            requireNonNull(domain, "domain is null");
            columns.add(new ColumnDomain(columnId, domain));
            return this;
        }

        public TupleDomainOrcPredicateBuilder setBloomFiltersEnabled(boolean bloomFiltersEnabled)
        {
            this.bloomFiltersEnabled = bloomFiltersEnabled;
            return this;
        }

        public TupleDomainOrcPredicate build()
        {
            return new TupleDomainOrcPredicate(columns, bloomFiltersEnabled);
        }
    }

    private static class ColumnDomain
    {
        private final OrcColumnId columnId;
        private final Domain domain;

        public ColumnDomain(OrcColumnId columnId, Domain domain)
        {
            this.columnId = requireNonNull(columnId, "columnId is null");
            this.domain = requireNonNull(domain, "domain is null");
        }

        public OrcColumnId getColumnId()
        {
            return columnId;
        }

        public Domain getDomain()
        {
            return domain;
        }

        @Override
        public String toString()
        {
            return toStringHelper(this)
                    .add("columnId", columnId)
                    .add("domain", domain)
                    .toString();
        }
    }
}
