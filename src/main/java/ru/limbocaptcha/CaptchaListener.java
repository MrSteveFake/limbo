package ru.limbocaptcha;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.elytrium.limboapi.api.Limbo;
import net.elytrium.limboapi.api.LimboFactory;
import net.elytrium.limboapi.api.chunk.VirtualWorld;
import net.elytrium.limboapi.api.player.LimboPlayer;
import net.elytrium.limboauth.LimboAuth;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class CaptchaListener {
    private final ProxyServer server;
    private final LimboCaptcha plugin;
    private final ConfigManager config;
    private final LimboFactory limboFactory;
    private final Map<UUID, String> pendingCaptcha = new HashMap<>();
    private final Map<UUID, ScheduledFuture> timeoutTasks = new HashMap<>();
    private final Map<UUID, LimboPlayer> limboPlayers = new HashMap<>();
    private Limbo captchaLimbo;
    
    public CaptchaListener(LimboCaptcha plugin, ProxyServer server, ConfigManager config) {
        this.plugin = plugin;
        this.server = server;
        this.config = config;
        this.limboFactory = (LimboFactory) server.getPluginManager().getPlugin("limboapi").get().getInstance().get();
    }
    
    public void initLimbo() {
        VirtualWorld world = limboFactory.createVirtualWorld(
            net.elytrium.limboapi.api.material.WorldVersion.MINECRAFT_1_21,
            0, 100, 0
        );
        
        captchaLimbo = limboFactory.createLimbo(world)
            .setName("CaptchaLimbo")
            .setReadTimeout(config.getTimeout() * 1000)
            .setWorldTime(6000L)
            .setGameMode(org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode.ADVENTURE);
    }
    
    @Subscribe
    public void onLogin(LoginEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        
        // Генерируем уникальный токен
        String token = UUID.randomUUID().toString().substring(0, 8);
        pendingCaptcha.put(uuid, token);
        
        // Создаем ссылку на капчу
        String captchaUrl = "https://" + config.getDomain() + "/captcha?player=" + 
            player.getUsername() + "&token=" + token + "&sitekey=" + config.getSiteKey();
        
        // Планируем таймаут
        ScheduledFuture timeoutTask = server.getScheduler()
            .buildTask(plugin, () -> handleCaptchaTimeout(player))
            .delay(config.getTimeout(), TimeUnit.SECONDS)
            .schedule();
        
        timeoutTasks.put(uuid, timeoutTask);
        
        // Отправляем в лимбо и показываем сообщения
        server.getScheduler().buildTask(plugin, () -> {
            captchaLimbo.spawnPlayer(player, new net.elytrium.limboapi.api.LimboSessionHandler() {
                @Override
                public void onSpawn(LimboPlayer limboPlayer) {
                    limboPlayers.put(uuid, limboPlayer);
                    
                    // Отправляем тайтлы
                    player.showTitle(net.kyori.adventure.title.Title.title(
                        LegacyComponentSerializer.legacyAmpersand().deserialize(config.getTitle()),
                        LegacyComponentSerializer.legacyAmpersand().deserialize(config.getSubtitle()),
                        net.kyori.adventure.title.Title.Times.of(
                            java.time.Duration.ofMillis(500),
                            java.time.Duration.ofSeconds(70),
                            java.time.Duration.ofMillis(500)
                        )
                    ));
                    
                    // Отправляем сообщения в чат
                    for (String line : config.getChatMessage()) {
                        String formattedLine = line.replace("%link%", captchaUrl);
                        player.sendMessage(
                            LegacyComponentSerializer.legacyAmpersand().deserialize(formattedLine)
                        );
                    }
                }
                
                @Override
                public void onDisconnect() {
                    limboPlayers.remove(uuid);
                }
            });
        }).schedule();
    }
    
    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        
        if (pendingCaptcha.containsKey(uuid)) {
            // Проверяем, не пытается ли игрок подключиться к серверу авторизации
            RegisteredServer targetServer = event.getOriginalServer();
            RegisteredServer authServer = getAuthServer();
            
            if (authServer != null && targetServer.equals(authServer)) {
                // Разрешаем подключение к серверу авторизации только после прохождения капчи
                if (pendingCaptcha.containsKey(uuid)) {
                    event.setResult(ServerPreConnectEvent.ServerResult.denied());
                }
            }
        }
    }
    
    public void handleCaptchaSuccess(Player player, String token) {
        UUID uuid = player.getUniqueId();
        
        if (!pendingCaptcha.containsKey(uuid)) {
            return;
        }
        
        String expectedToken = pendingCaptcha.get(uuid);
        
        if (expectedToken.equals(token)) {
            // Отменяем таймаут
            ScheduledFuture task = timeoutTasks.remove(uuid);
            if (task != null) {
                task.cancel(false);
            }
            
            pendingCaptcha.remove(uuid);
            
            // Отправляем сообщение об успехе
            player.sendMessage(
                LegacyComponentSerializer.legacyAmpersand().deserialize(config.getSuccessMessage())
            );
            
            // Перемещаем игрока на сервер авторизации
            RegisteredServer authServer = getAuthServer();
            if (authServer != null) {
                // Сначала убираем из лимбо
                LimboPlayer limboPlayer = limboPlayers.remove(uuid);
                if (limboPlayer != null) {
                    captchaLimbo.removePlayer(player);
                }
                
                // Подключаем к серверу авторизации
                player.createConnectionRequest(authServer).connectWithIndication();
            }
        } else {
            handleCaptchaFail(player);
        }
    }
    
    private void handleCaptchaTimeout(Player player) {
        UUID uuid = player.getUniqueId();
        
        if (!pendingCaptcha.containsKey(uuid)) {
            return;
        }
        
        pendingCaptcha.remove(uuid);
        timeoutTasks.remove(uuid);
        
        handleCaptchaFail(player);
    }
    
    private void handleCaptchaFail(Player player) {
        UUID uuid = player.getUniqueId();
        
        // Убираем из лимбо
        LimboPlayer limboPlayer = limboPlayers.remove(uuid);
        if (limboPlayer != null) {
            captchaLimbo.removePlayer(player);
        }
        
        // Кикаем игрока
        Component kickMessage = LegacyComponentSerializer.legacyAmpersand()
            .deserialize(config.getFailedMessage());
        
        player.disconnect(kickMessage);
    }
    
    private RegisteredServer getAuthServer() {
        // Получаем сервер авторизации из LimboAuth
        Optional<RegisteredServer> authServer = server.getServer("auth");
        
        if (authServer.isEmpty()) {
            // Пробуем получить первый сервер из конфигурации LimboAuth
            Collection<RegisteredServer> servers = server.getAllServers();
            for (RegisteredServer srv : servers) {
                if (srv.getServerInfo().getName().toLowerCase().contains("auth")) {
                    return srv;
                }
            }
        }
        
        return authServer.orElse(null);
    }
    
    public boolean verifyCaptcha(String remoteIp, String captchaResponse) {
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost post = new HttpPost("https://www.google.com/recaptcha/api/siteverify");
            post.setHeader("Content-Type", "application/x-www-form-urlencoded");
            
            String params = "secret=" + config.getSecretKey() + 
                          "&response=" + captchaResponse + 
                          "&remoteip=" + remoteIp;
            
            post.setEntity(new StringEntity(params));
            
            try (CloseableHttpResponse response = httpClient.execute(post)) {
                String json = EntityUtils.toString(response.getEntity());
                JsonObject jsonObject = JsonParser.parseString(json).getAsJsonObject();
                
                return jsonObject.get("success").getAsBoolean();
            }
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }
    
    public Limbo getCaptchaLimbo() {
        return captchaLimbo;
    }
}
