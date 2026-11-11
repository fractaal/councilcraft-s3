package skyislands;

/** Added to RandomSpreadStructurePlacement by skyislands.mixin.RandomSpreadStructurePlacementMixin. */
public interface DenserPlacement {
    /** Replaces the grid (in chunks) the structure set is placed on. */
    void skyislands$setGrid(int spacing, int separation);

    /** The grid from the structure set's own definition, before any change. */
    int skyislands$baseSpacing();

    int skyislands$baseSeparation();
}
