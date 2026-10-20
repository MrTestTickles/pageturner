package pageturner;

/** Immutable position token passed between reader and sync layers. */
record BookPosition(int chapter, int charOffset) {}
