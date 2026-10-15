package com.example.demo.award.model;

/**
 * Filter and pagination criteria for querying resigned, terminated, or declined awards.
 */
public record ResignedAwardsQueryCriteria(
        int page,
        int size,
        String search,
        String categoria,
        String tipus,
        String reasonFilter,
        String statusType,
        String year,
        String sortBy,
        String sortOrder,
        boolean exportAll
) {
    public ResignedAwardsQueryCriteria {
        if (page < 0) page = 0;
        if (size <= 0) size = 20;
        if (sortBy == null || sortBy.isBlank()) sortBy = "date";
        if (sortOrder == null || sortOrder.isBlank()) sortOrder = "desc";
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int page = 0;
        private int size = 20;
        private String search;
        private String categoria;
        private String tipus;
        private String reasonFilter;
        private String statusType;
        private String year;
        private String sortBy = "date";
        private String sortOrder = "desc";
        private boolean exportAll = false;

        public Builder page(int page) { this.page = page; return this; }
        public Builder size(int size) { this.size = size; return this; }
        public Builder search(String search) { this.search = search; return this; }
        public Builder categoria(String categoria) { this.categoria = categoria; return this; }
        public Builder tipus(String tipus) { this.tipus = tipus; return this; }
        public Builder reasonFilter(String reasonFilter) { this.reasonFilter = reasonFilter; return this; }
        public Builder statusType(String statusType) { this.statusType = statusType; return this; }
        public Builder year(String year) { this.year = year; return this; }
        public Builder sortBy(String sortBy) { this.sortBy = sortBy; return this; }
        public Builder sortOrder(String sortOrder) { this.sortOrder = sortOrder; return this; }
        public Builder exportAll(boolean exportAll) { this.exportAll = exportAll; return this; }

        public ResignedAwardsQueryCriteria build() {
            return new ResignedAwardsQueryCriteria(
                    page, size, search, categoria, tipus,
                    reasonFilter, statusType, year, sortBy, sortOrder, exportAll
            );
        }
    }
}
