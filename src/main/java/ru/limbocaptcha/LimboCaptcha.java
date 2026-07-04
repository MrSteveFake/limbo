package ru.limbocaptcha;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;

import java.nio.file.Path;
import java.util.logging.Logger;

@Plugin(
    id = "limbocaptcha",
    name = "LimboCaptcha",
    version = "1.0.0",
    description = "Captcha plugin for LimboAuth",
    authors = {"YourName"},
    dependencies = {
        @Plugin.Dependency(id = "limboapi"),
        @Plugin.Dependency(id = "limboauth")
    }
)
public class LimboCaptcha {
    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private ConfigManager configManager;
    private CaptchaListener captchaListener;
    
    @Inject
    public LimboCaptcha(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }
    
    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        // Загружаем конфигурацию
        configManager = new ConfigManager(dataDirectory);
        configManager.loadConfig();
        
        // Инициализируем слушатель
        captchaListener = new CaptchaListener(this, server, configManager);
        captchaListener.initLimbo();
        
        // Регистрируем слушатель событий
        server.getEventManager().register(this, captchaListener);
        
        logger.info("LimboCaptcha plugin enabled!");
    }
}
