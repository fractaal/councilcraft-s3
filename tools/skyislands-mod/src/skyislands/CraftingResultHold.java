package skyislands;

/**
 * Added to Sophisticated Core's CraftingUpgradeContainer (skyislands.mixin.CraftingUpgradeHoldMixin) so its result slot
 * (skyislands.mixin.CraftingUpgradeTakeMixin) can hold back the container's result updates while a craft is taken.
 */
public interface CraftingResultHold {
    void skyislands$holdResult();

    void skyislands$releaseResult();
}
