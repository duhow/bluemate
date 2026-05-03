package net.duhowpi.bluemate

object DeviceNameGenerator {

    private val adjectives = listOf(
        "Amber", "Azure", "Beige", "Bronze", "Cobalt",
        "Coral", "Crimson", "Cyan", "Denim", "Ebony",
        "Emerald", "Fawn", "Fuchsia", "Golden", "Indigo",
        "Ivory", "Jade", "Khaki", "Lavender", "Lilac",
        "Lime", "Magenta", "Maroon", "Navy", "Ochre",
        "Olive", "Orchid", "Peach", "Plum", "Ruby",
        "Russet", "Sage", "Sapphire", "Scarlet", "Silver",
        "Teal", "Topaz", "Turquoise", "Umber", "Violet"
    )

    private val nouns = listOf(
        "Albatross", "Badger", "Beaver", "Bison", "Bobcat",
        "Buffalo", "Condor", "Cougar", "Crane", "Dingo",
        "Eagle", "Falcon", "Ferret", "Fox", "Gecko",
        "Hawk", "Ibis", "Jackal", "Jaguar", "Kestrel",
        "Lynx", "Magpie", "Marmot", "Moose", "Otter",
        "Panda", "Pelican", "Puffin", "Raven", "Robin",
        "Salamander", "Sparrow", "Stork", "Tiger", "Viper",
        "Walrus", "Weasel", "Wolf", "Wolverine", "Wombat"
    )

    /**
     * Generates a deterministic human-readable name from [major] and [minor].
     *
     * The adjective index is derived from [major] modulo the adjective list size,
     * and the noun index from [minor] modulo the noun list size.  Both values are
     * masked to 16 bits so negative Java ints behave consistently.
     */
    fun generate(major: Int, minor: Int): String {
        val adj = adjectives[(major and 0xFFFF) % adjectives.size]
        val noun = nouns[(minor and 0xFFFF) % nouns.size]
        return "$adj $noun"
    }
}
