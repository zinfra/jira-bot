package com.wire.bots.github.resource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wire.bots.github.BotConfig;
import com.wire.bots.github.model.Response;
import com.wire.bots.sdk.ClientRepo;
import com.wire.bots.sdk.Logger;
import com.wire.bots.sdk.Util;
import com.wire.bots.sdk.WireClient;
import com.wire.bots.sdk.assets.Picture;
import com.wire.bots.sdk.models.AssetKey;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import java.io.File;
import java.math.BigInteger;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

@Path("/github")
public class GitHubResource {

    private final BotConfig conf;
    //private final Executor exec;
    private ClientRepo repo;

    public GitHubResource(ClientRepo repo, BotConfig conf) {
        this.repo = repo;
        this.conf = conf;
    }

    @POST
    @Path("/{botId}")
    public javax.ws.rs.core.Response broadcast(@PathParam("botId") String botId,
                                               @HeaderParam("X-GitHub-Event") String event,
                                               @HeaderParam("X-Hub-Signature") String signature,
                                               @HeaderParam("X-GitHub-Delivery") String delivery,
                                               String payload) throws Exception {

        Logger.info("Event: %s, Signature: %s, Delivery: %s", event, signature, delivery);

        String secret = Util.readLine(new File(String.format("%s/%s/secret", conf.getCryptoDir(), botId)));
        String challenge = getSha(payload, secret);
        if (!challenge.equals(signature)) {
            Logger.warning("Invalid sha");
            return javax.ws.rs.core.Response.
                    status(403).
                    build();
        }

        ObjectMapper mapper = new ObjectMapper();
        WireClient client = repo.getWireClient(botId);
        Response response = mapper.readValue(payload, Response.class);

        switch (event) {
            case "pull_request": {
                switch (response.action) {
                    case "opened": {
                        String title = String.format("[%s] New PR #%s: %s", response.repository.fullName,
                                response.pr.number, response.pr.title);
                        sendLinkPreview(client, title, response.pr.url, event + "_" + response.action);
                        break;
                    }
                    case "closed": {
                        String mergedOrClosed = response.pr.merged ? "merged" : "closed";
                        String title = String.format("[%s] PR #%s %s: %s", response.repository.fullName,
                                response.pr.number, mergedOrClosed, response.pr.title);
                        sendLinkPreview(client, title, response.pr.url, event + "_" + mergedOrClosed);
                        break;
                    }
                }
                break;
            }
            case "issues": {
                switch (response.action) {
                    case "opened":
                    case "reopened": {
                        String title = String.format("[%s] New Issue #%s: %s", response.repository.fullName,
                                response.issue.number, response.issue.title);
                        sendLinkPreview(client, title, response.issue.url, event + "_" + response.action);
                        break;
                    }
                    case "closed": {
                        String title = String.format("[%s] Issue #%s closed: %s", response.repository.fullName, response.pr.number, response.pr.title);
                        sendLinkPreview(client, title, response.pr.url, event + "_" + response.action);
                        break;
                    }
                }
                break;
            }
        }

        return javax.ws.rs.core.Response.
                ok().
                build();
    }

    private void sendLinkPreview(WireClient client, String url, String title, String resourceImageName) throws Exception {
        Picture preview = null;
        URL imageUrl = GitHubResource.class.getResource("/images/" + resourceImageName + ".png");
        if (imageUrl != null) {
            URI uri = new URI(imageUrl.toString());
            String path = uri.getPath();
            byte[] encoded = Files.readAllBytes(Paths.get(path));
            preview = new Picture(encoded);
            preview.setPublic(true);
            AssetKey assetKey = client.uploadAsset(preview);
            preview.setAssetKey(assetKey.key);
        }
        client.sendLinkPreview(url, title, preview);
    }

    private String getSha(String payload, String secret) throws NoSuchAlgorithmException, InvalidKeyException {
        Mac hmac = Mac.getInstance("HmacSHA1");
        hmac.init(new SecretKeySpec(secret.getBytes(Charset.forName("UTF-8")), "HmacSHA1"));
        byte[] bytes = hmac.doFinal(payload.getBytes(Charset.forName("UTF-8")));
        return String.format("sha1=%040x", new BigInteger(1, bytes));
    }
}