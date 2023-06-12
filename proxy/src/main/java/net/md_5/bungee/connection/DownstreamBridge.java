package net.md_5.bungee.connection;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.tree.CommandNode;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import java.io.DataInput;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import net.md_5.bungee.ServerConnection;
import net.md_5.bungee.ServerConnection.KeepAliveData;
import net.md_5.bungee.UserConnection;
import net.md_5.bungee.Util;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.event.ServerConnectEvent;
import net.md_5.bungee.api.event.ServerDisconnectEvent;
import net.md_5.bungee.api.event.ServerKickEvent;
import net.md_5.bungee.api.event.TabCompleteResponseEvent;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.score.Objective;
import net.md_5.bungee.api.score.Position;
import net.md_5.bungee.api.score.Score;
import net.md_5.bungee.api.score.Scoreboard;
import net.md_5.bungee.api.score.Team;
import net.md_5.bungee.chat.ComponentSerializer;
import net.md_5.bungee.entitymap.EntityMap;
import net.md_5.bungee.netty.ChannelWrapper;
import net.md_5.bungee.netty.PacketHandler;
import net.md_5.bungee.protocol.DefinedPacket;
import net.md_5.bungee.protocol.PacketWrapper;
import net.md_5.bungee.protocol.ProtocolConstants;
import net.md_5.bungee.protocol.packet.BossBar;
import net.md_5.bungee.protocol.packet.Commands;
import net.md_5.bungee.protocol.packet.KeepAlive;
import net.md_5.bungee.protocol.packet.Kick;
import net.md_5.bungee.protocol.packet.PlayerListItem;
import net.md_5.bungee.protocol.packet.PlayerListItemRemove;
import net.md_5.bungee.protocol.packet.PlayerListItemUpdate;
import net.md_5.bungee.protocol.packet.PluginMessage;
import net.md_5.bungee.protocol.packet.Respawn;
import net.md_5.bungee.protocol.packet.ScoreboardDisplay;
import net.md_5.bungee.protocol.packet.ScoreboardObjective;
import net.md_5.bungee.protocol.packet.ScoreboardScore;
import net.md_5.bungee.protocol.packet.ServerData;
import net.md_5.bungee.protocol.packet.SetCompression;
import net.md_5.bungee.protocol.packet.TabCompleteResponse;
import net.md_5.bungee.tab.TabList;

@RequiredArgsConstructor
public class DownstreamBridge extends PacketHandler
{

    // #3246: Recent versions of MinecraftForge alter Vanilla behaviour and require a command so that the executable flag is set
    // If the flag is not set, then the command will appear and successfully tab complete, but cannot be successfully executed
    private static final com.mojang.brigadier.Command DUMMY_COMMAND = (context) ->
    {
        return 0;
    };
    //
    private final ProxyServer bungee;
    private final UserConnection con;
    private final ServerConnection server;

    @Override
    public void exception(Throwable t) throws Exception
    {
        if ( server.isObsolete() )
        {
            // do not perform any actions if the user has already moved
            return;
        }

        ServerInfo def = con.updateAndGetNextServer( server.getInfo() );
        if ( def != null )
        {
            server.setObsolete( true );
            con.connectNow( def, ServerConnectEvent.Reason.SERVER_DOWN_REDIRECT );
            con.sendMessage( bungee.getTranslation( "server_went_down" ) );
        } else
        {
            con.disconnect( Util.exception( t ) );
        }
    }

    @Override
    public void disconnected(ChannelWrapper channel) throws Exception
    {
        // We lost connection to the server
        server.getInfo().removePlayer( con );
        if ( bungee.getReconnectHandler() != null )
        {
            bungee.getReconnectHandler().setServer( con );
        }

        if ( !server.isObsolete() )
        {
            con.disconnect( bungee.getTranslation( "lost_connection" ) );
        }

        ServerDisconnectEvent serverDisconnectEvent = new ServerDisconnectEvent( con, server.getInfo() );
        bungee.getPluginManager().callEvent( serverDisconnectEvent );
    }

    @Override
    public boolean shouldHandle(PacketWrapper packet) throws Exception
    {
        return !server.isObsolete();
    }

    @Override
    public void handle(PacketWrapper packet) throws Exception
    {
        EntityMap rewrite = con.getEntityRewrite();
        if ( rewrite != null )
        {
            rewrite.rewriteClientbound( packet.buf, con.getServerEntityId(), con.getClientEntityId(), con.getPendingConnection().getVersion() );
        }
        con.sendPacket( packet );
    }

    @Override
    public void handle(KeepAlive alive) throws Exception
    {
        int timeout = bungee.getConfig().getTimeout();
        if ( timeout <= 0 || server.getKeepAlives().size() < timeout / 50 ) // Some people disable timeout, otherwise allow a theoretical maximum of 1 keepalive per tick
        {
            server.getKeepAlives().add( new KeepAliveData( alive.getRandomId(), System.currentTimeMillis() ) );
        }
    }

    @Override
    public void handle(PlayerListItem playerList) throws Exception
    {
        con.getTabListHandler().onUpdate( TabList.rewrite( playerList ) );
        throw CancelSendSignal.INSTANCE; // Always throw because of profile rewriting
    }

    @Override
    public void handle(PlayerListItemRemove playerList) throws Exception
    {
        con.getTabListHandler().onUpdate( TabList.rewrite( playerList ) );
        throw CancelSendSignal.INSTANCE; // Always throw because of profile rewriting
    }

    @Override
    public void handle(PlayerListItemUpdate playerList) throws Exception
    {
        con.getTabListHandler().onUpdate( TabList.rewrite( playerList ) );
        throw CancelSendSignal.INSTANCE; // Always throw because of profile rewriting
    }

    @Override
    public void handle(ScoreboardObjective objective) throws Exception
    {
        Scoreboard serverScoreboard = con.getServerSentScoreboard();
        switch ( objective.getAction() )
        {
            case 0:
                serverScoreboard.addObjective( new Objective( objective.getName(), objective.getValue(), objective.getType().toString() ) );
                break;
            case 1:
                serverScoreboard.removeObjective( objective.getName() );
                break;
            case 2:
                Objective oldObjective = serverScoreboard.getObjective( objective.getName() );
                if ( oldObjective != null )
                {
                    oldObjective.setValue( objective.getValue() );
                    oldObjective.setType( objective.getType().toString() );
                }
                break;
            default:
                throw new IllegalArgumentException( "Unknown objective action: " + objective.getAction() );
        }
    }

    @Override
    public void handle(ScoreboardScore score) throws Exception
    {
        Scoreboard serverScoreboard = con.getServerSentScoreboard();
        switch ( score.getAction() )
        {
            case 0:
                Score s = new Score( score.getItemName(), score.getScoreName(), score.getValue() );
                serverScoreboard.removeScore( score.getItemName() );
                serverScoreboard.addScore( s );
                break;
            case 1:
                serverScoreboard.removeScore( score.getItemName() );
                break;
            default:
                throw new IllegalArgumentException( "Unknown scoreboard action: " + score.getAction() );
        }
    }

    @Override
    public void handle(ScoreboardDisplay displayScoreboard) throws Exception
    {
        Scoreboard serverScoreboard = con.getServerSentScoreboard();
        serverScoreboard.setName( displayScoreboard.getName() );
        serverScoreboard.setPosition( Position.values()[displayScoreboard.getPosition()] );
    }

    @Override
    public void handle(net.md_5.bungee.protocol.packet.Team team) throws Exception
    {
        Scoreboard serverScoreboard = con.getServerSentScoreboard();
        // Remove team and move on
        if ( team.getMode() == 1 )
        {
            serverScoreboard.removeTeam( team.getName() );
            return;
        }

        // Create or get old team
        Team t;
        if ( team.getMode() == 0 )
        {
            t = new Team( team.getName() );
            serverScoreboard.addTeam( t );
        } else
        {
            t = serverScoreboard.getTeam( team.getName() );
        }

        if ( t != null )
        {
            if ( team.getMode() == 0 || team.getMode() == 2 )
            {
                t.setDisplayName( team.getDisplayName() );
                t.setPrefix( team.getPrefix() );
                t.setSuffix( team.getSuffix() );
                t.setFriendlyFire( team.getFriendlyFire() );
                t.setNameTagVisibility( team.getNameTagVisibility() );
                t.setCollisionRule( team.getCollisionRule() );
                t.setColor( team.getColor() );
            }
            if ( team.getPlayers() != null )
            {
                for ( String s : team.getPlayers() )
                {
                    if ( team.getMode() == 0 || team.getMode() == 3 )
                    {
                        t.addPlayer( s );
                    } else if ( team.getMode() == 4 )
                    {
                        t.removePlayer( s );
                    }
                }
            }
        }
        if ( !con.getServer().getInfo().getName().equalsIgnoreCase( "cv7wargames" ) && !con.getServer().getInfo().getName().equalsIgnoreCase( "cv7bedwars" ) && !team.getName().startsWith( "LD_" ) && !team.getName().startsWith( "CIT-" ) )
        {
            throw CancelSendSignal.INSTANCE;
        }
    }

    @Override
    @SuppressWarnings("checkstyle:avoidnestedblocks")
    public void handle(PluginMessage pluginMessage) throws Exception
    {
        DataInput in = pluginMessage.getStream();
        PluginMessageEvent event = new PluginMessageEvent( server, con, pluginMessage.getTag(), pluginMessage.getData().clone() );

        if ( bungee.getPluginManager().callEvent( event ).isCancelled() )
        {
            throw CancelSendSignal.INSTANCE;
        }

        if ( pluginMessage.getTag().equals( con.getPendingConnection().getVersion() >= ProtocolConstants.MINECRAFT_1_13 ? "minecraft:brand" : "MC|Brand" ) )
        {
            ByteBuf brand = Unpooled.wrappedBuffer( pluginMessage.getData() );
            String serverBrand = DefinedPacket.readString( brand );
            brand.release();

            Preconditions.checkState( !serverBrand.contains( bungee.getName() ), "Cannot connect proxy to itself!" );

            brand = ByteBufAllocator.DEFAULT.heapBuffer();
            DefinedPacket.writeString( bungee.getName() + " (" + bungee.getVersion() + ")" + " <- " + serverBrand, brand );
            pluginMessage.setData( DefinedPacket.toArray( brand ) );
            brand.release();
            // changes in the packet are ignored so we need to send it manually
            con.unsafe().sendPacket( pluginMessage );
            throw CancelSendSignal.INSTANCE;
        }

        if ( pluginMessage.getTag().equals( "BungeeCord" ) )
        {
            throw CancelSendSignal.INSTANCE;
        }
    }

    @Override
    public void handle(Kick kick) throws Exception
    {
        ServerInfo def = con.updateAndGetNextServer( server.getInfo() );
        ServerKickEvent event = bungee.getPluginManager().callEvent( new ServerKickEvent( con, server.getInfo(), ComponentSerializer.parse( kick.getMessage() ), def, ServerKickEvent.State.CONNECTED ) );
        if ( event.isCancelled() && event.getCancelServer() != null )
        {
            con.connectNow( event.getCancelServer(), ServerConnectEvent.Reason.KICK_REDIRECT );
        } else
        {
            con.disconnect0( event.getKickReasonComponent() ); // TODO: Prefix our own stuff.
        }
        server.setObsolete( true );
        throw CancelSendSignal.INSTANCE;
    }

    @Override
    public void handle(SetCompression setCompression) throws Exception
    {
        server.getCh().setCompressionThreshold( setCompression.getThreshold() );
    }

    @Override
    public void handle(TabCompleteResponse tabCompleteResponse) throws Exception
    {
        List<String> commands = tabCompleteResponse.getCommands();
        if ( commands == null )
        {
            commands = Lists.transform( tabCompleteResponse.getSuggestions().getList(), new Function<Suggestion, String>()
            {
                @Override
                public String apply(Suggestion input)
                {
                    return input.getText();
                }
            } );
        }

        TabCompleteResponseEvent tabCompleteResponseEvent = new TabCompleteResponseEvent( server, con, new ArrayList<>( commands ) );
        if ( !bungee.getPluginManager().callEvent( tabCompleteResponseEvent ).isCancelled() )
        {
            // Take action only if modified
            if ( !commands.equals( tabCompleteResponseEvent.getSuggestions() ) )
            {
                if ( tabCompleteResponse.getCommands() != null )
                {
                    // Classic style
                    tabCompleteResponse.setCommands( tabCompleteResponseEvent.getSuggestions() );
                } else
                {
                    // Brigadier style
                    final StringRange range = tabCompleteResponse.getSuggestions().getRange();
                    tabCompleteResponse.setSuggestions( new Suggestions( range, Lists.transform( tabCompleteResponseEvent.getSuggestions(), new Function<String, Suggestion>()
                    {
                        @Override
                        public Suggestion apply(String input)
                        {
                            return new Suggestion( range, input );
                        }
                    } ) ) );
                }
            }

            con.unsafe().sendPacket( tabCompleteResponse );
        }

        throw CancelSendSignal.INSTANCE;
    }

    @Override
    public void handle(BossBar bossBar)
    {
        switch ( bossBar.getAction() )
        {
            // Handle add bossbar
            case 0:
                con.getSentBossBars().add( bossBar.getUuid() );
                break;
            // Handle remove bossbar
            case 1:
                con.getSentBossBars().remove( bossBar.getUuid() );
                break;
        }
    }

    @Override
    public void handle(Respawn respawn)
    {
        con.setDimension( respawn.getDimension() );
    }

    @Override
    public void handle(Commands commands) throws Exception
    {
        boolean modified = false;

        for ( Map.Entry<String, Command> command : bungee.getPluginManager().getCommands() )
        {
            if ( !bungee.getDisabledCommands().contains( command.getKey() ) && commands.getRoot().getChild( command.getKey() ) == null && command.getValue().hasPermission( con ) )
            {
                CommandNode dummy = LiteralArgumentBuilder.literal( command.getKey() ).executes( DUMMY_COMMAND )
                        .then( RequiredArgumentBuilder.argument( "args", StringArgumentType.greedyString() )
                                .suggests( Commands.SuggestionRegistry.ASK_SERVER ).executes( DUMMY_COMMAND ) )
                        .build();
                commands.getRoot().addChild( dummy );

                modified = true;
            }
        }

        if ( modified )
        {
            con.unsafe().sendPacket( commands );
            throw CancelSendSignal.INSTANCE;
        }
    }

    @Override
    public void handle(ServerData serverData) throws Exception
    {
        // 1.19.4 doesn't allow empty MOTD and we probably don't want to simulate a ping event to get the "correct" one
        // serverData.setMotd( null );
        // serverData.setIcon( null );
        // con.unsafe().sendPacket( serverData );
        throw CancelSendSignal.INSTANCE;
    }

    @Override
    public String toString()
    {
        return "[" + con.getName() + "] <-> DownstreamBridge <-> [" + server.getInfo().getName() + "]";
    }
}
