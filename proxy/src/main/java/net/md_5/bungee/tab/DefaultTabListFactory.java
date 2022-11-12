package net.md_5.bungee.tab;

import net.md_5.bungee.api.connection.ProxiedPlayer;

public class DefaultTabListFactory
{
    public TabList createTabList(ProxiedPlayer player)
    {
        return new ServerUnique( player );
    }
}
