-- Wave 9 w4-reports-distance-crm (7.1): delivery_distance.average.v1
-- (MetricRegistry) is the first metric registered with Aggregation.AVERAGE
-- and MetricUnit.METERS. reporting.metric_definitions' own CHECK constraints
-- (V0031) enumerate the vocabulary as it stood then and reject the new
-- values outright -- the registry sync that mirrors MetricRegistry.all()
-- into this table fails at startup otherwise. Not a reserved number: V0387
-- (the wave's own reservation) only covered the fact_order column: this gap
-- was found wiring the metric into the dictionary, one column and one
-- constraint over.
ALTER TABLE reporting.metric_definitions
    DROP CONSTRAINT ck_metric_definition_aggregation,
    ADD CONSTRAINT ck_metric_definition_aggregation CHECK (aggregation IN (
        'SUM', 'COUNT', 'COUNT_DISTINCT', 'RATIO', 'MEDIAN', 'DISTRIBUTION', 'AVERAGE')),
    DROP CONSTRAINT ck_metric_definition_unit,
    ADD CONSTRAINT ck_metric_definition_unit CHECK (unit IN (
        'MONEY_SOM', 'COUNT', 'SECONDS', 'MINUTES', 'BASIS_POINTS', 'METERS'));

-- No GRANT block: reporting.metric_definitions already has one from V0031,
-- and a GRANT is per-table, not per-column or per-constraint.
