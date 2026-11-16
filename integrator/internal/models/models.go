package models

import "time"

// HabronRawRow represents a single ready-to-store product + size stock row extracted
// directly from Habron SQL Server via a read-only SELECT query.
// Neither PostgreSQL nor the Go Integrator generates barcodes or fabricates fallback values.
type HabronRawRow struct {
	SourceProductKey string
	Name             string
	Brand            string
	Article          *string
	Color            *string
	MRP              int
	Party            string
	Barcode          string
	Brandcode        string
	Size             string
	SizeSortOrder    int
	Quantity         int
	TotalIn          int
	TotalOut         int
	AgeDays          *int
	SourceUpdatedAt  time.Time
}

// HabronProductRecord aggregates product-level attributes and its nested size variants
// keyed by SourceProductKey.
type HabronProductRecord struct {
	SourceProductKey string
	Name             string
	Brand            string
	Article          *string
	Color            *string
	MRP              int
	Party            string
	Barcode          string
	Brandcode        string
	SourceUpdatedAt  time.Time
	Stocks           []HabronStockVariant
}

// HabronStockVariant represents a single size row for a HabronProductRecord.
type HabronStockVariant struct {
	Size            string
	SizeSortOrder   int
	Quantity        int
	TotalIn         int
	TotalOut        int
	AgeDays         *int
	SourceUpdatedAt time.Time
}

// LookupValue represents a normalized + display value pair for lookup_* tables.
type LookupValue struct {
	NormalizedValue string
	DisplayValue    string
	SizeSortOrder   int
}

// SyncStats summarizes the work performed during a single sync cycle.
type SyncStats struct {
	IsFullReconciliation bool
	ProductsUpserted     int
	StocksUpserted       int
	ProductsDeleted      int
	StocksDeleted        int
	LookupsUpserted      int
	MaxSourceUpdatedAt   time.Time
	Duration             time.Duration
}
