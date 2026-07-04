package ru.limbocaptcha;

import com.velocitypowered.api.plugin.annotation.DataDirectory;
import ninja.leaping.configurate.ConfigurationNode;
import ninja.leaping.configurate.yaml.YAMLConfigurationLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ConfigManager {
    private final Path dataDirectory;
    private ConfigurationNode config;
    
    public ConfigManager(Path dataDirectory) {
        this.dataDirectory = dataDirectory;
    }
    
    public void loadConfig() {
        Path configPath = dataDirectory.resolve("config.yml");
        
        if (!Files.exists(configPath)) {
            try {
                Files.createDirectories(dataDirectory);
                try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
                    Files.copy(in, configPath);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        
        try {
            config = YAMLConfigurationLoader.builder()
                .setPath(configPath)
                .build()
                .load();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    public String getSiteKey() {
        return config.getNode("recaptcha", "site-key").getString("your-site-key");
    }
    
    public String getSecretKey() {
        return config.getNode("recaptcha", "secret-key").getString("your-secret-key");
    }
    
    public String getDomain() {
        return config.getNode("recaptcha", "domain").getString("https://your-domain.com");
    }
    
    public String getLimboName() {
        return config.getNode("limbo", "name").getString("captcha");
    }
    
    public int getTimeout() {
        return config.getNode("timeout").getInt(120);
    }
    
    public String getTitle() {
        return config.getNode("messages", "captcha-title").getString("&6&lПройдите капчу!");
    }
    
    public String getSubtitle() {
        return config.getNode("messages", "captcha-subtitle").getString("&eДля продолжения нажмите на ссылку в чате");
    }
    
    public List<String> getChatMessage() {
        return config.getNode("messages", "captcha-chat").getList(String::valueOf, new ArrayList<>());
    }
    
    public String getFailedMessage() {
        return config.getNode("messages", "captcha-failed").getString("&cВы не прошли капчу или время истекло!");
    }
    
    public String getSuccessMessage() {
        return config.getNode("messages", "captcha-success").getString("&aКапча пройдена успешно!");
    }
}
