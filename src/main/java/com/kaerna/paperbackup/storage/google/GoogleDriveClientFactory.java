package com.kaerna.paperbackup.storage.google;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.UserCredentials;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Locale;

public class GoogleDriveClientFactory {

    private final String authMode;
    private final File credentialsFile;
    private final String oauthClientId;
    private final String oauthClientSecret;
    private final String oauthRefreshToken;

    public GoogleDriveClientFactory(
            String authMode,
            File credentialsFile,
            String oauthClientId,
            String oauthClientSecret,
            String oauthRefreshToken
    ) {
        this.authMode = authMode == null ? "OAUTH" : authMode.trim().toUpperCase(Locale.ROOT);
        this.credentialsFile = credentialsFile;
        this.oauthClientId = oauthClientId == null ? "" : oauthClientId.trim();
        this.oauthClientSecret = oauthClientSecret == null ? "" : oauthClientSecret.trim();
        this.oauthRefreshToken = oauthRefreshToken == null ? "" : oauthRefreshToken.trim();
    }

    public Drive create() throws IOException, GeneralSecurityException {
        GoogleCredentials credentials = createCredentials();
        return new Drive.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                GsonFactory.getDefaultInstance(),
                new HttpCredentialsAdapter(credentials)
        )
                .setApplicationName("PaperBackup")
                .build();
    }

    private GoogleCredentials createCredentials() throws IOException {
        if (authMode.equals("SERVICE_ACCOUNT")) {
            if (!credentialsFile.exists()) {
                throw new IOException("Google Drive service account file does not exist: " + credentialsFile.getPath());
            }
            try (FileInputStream input = new FileInputStream(credentialsFile)) {
                return GoogleCredentials.fromStream(input).createScoped(List.of(DriveScopes.DRIVE));
            }
        }

        if (!authMode.equals("OAUTH")) {
            throw new IOException("Unknown google-drive.auth-mode: " + authMode + ". Use OAUTH or SERVICE_ACCOUNT.");
        }
        if (oauthClientId.isBlank() || oauthClientSecret.isBlank() || oauthRefreshToken.isBlank()) {
            throw new IOException("Google Drive OAuth is not configured. Fill google-drive.oauth.client-id, client-secret, and refresh-token.");
        }

        return UserCredentials.newBuilder()
                .setClientId(oauthClientId)
                .setClientSecret(oauthClientSecret)
                .setRefreshToken(oauthRefreshToken)
                .build();
    }
}
