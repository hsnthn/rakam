package org.rakam.ui;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.google.common.base.Throwables;
import com.google.common.io.ByteStreams;
import com.google.inject.name.Named;
import org.rakam.analysis.JDBCPoolDataSource;
import org.rakam.config.EncryptionConfig;
import org.rakam.server.http.HttpService;
import org.rakam.server.http.RakamHttpRequest;
import org.rakam.server.http.annotations.ApiOperation;
import org.rakam.server.http.annotations.ApiParam;
import org.rakam.server.http.annotations.Authorization;
import org.rakam.server.http.annotations.BodyParam;
import org.rakam.server.http.annotations.CookieParam;
import org.rakam.server.http.annotations.IgnoreApi;
import org.rakam.server.http.annotations.JsonRequest;
import org.rakam.ui.user.WebUser;
import org.rakam.ui.user.WebUserService;
import org.rakam.util.AlreadyExistsException;
import org.rakam.util.JsonHelper;
import org.rakam.util.SuccessMessage;
import org.rakam.util.RakamException;
import org.skife.jdbi.v2.DBI;
import org.skife.jdbi.v2.Handle;
import org.skife.jdbi.v2.util.StringMapper;

import javax.inject.Inject;
import javax.net.ssl.SSLHandshakeException;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.SocketException;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST;
import static io.netty.handler.codec.http.HttpResponseStatus.FORBIDDEN;
import static io.netty.handler.codec.http.HttpResponseStatus.UNAUTHORIZED;
import static org.rakam.ui.user.WebUserHttpService.extractUserFromCookie;

@Path("/ui/cluster")
@IgnoreApi
public class ClusterService
        extends HttpService
{
    private final DBI dbi;
    private final EncryptionConfig encryptionConfig;
    private final WebUserService webUserService;

    @Inject
    public ClusterService(@Named("ui.metadata.jdbc") JDBCPoolDataSource dataSource,
            WebUserService webUserService,
            EncryptionConfig encryptionConfig)
    {
        dbi = new DBI(dataSource);
        this.webUserService = webUserService;
        this.encryptionConfig = encryptionConfig;
    }

    @JsonRequest
    @ApiOperation(value = "Register cluster", authorizations = @Authorization(value = "read_key"))
    @Path("/register")
    public SuccessMessage register(@CookieParam("session") String session,
            @BodyParam Cluster cluster)
    {
        int userId = extractUserFromCookie(session, encryptionConfig.getSecretKey());

        Optional<WebUser> webUser = webUserService.getUser(userId);
        if (webUser.get().readOnly) {
            throw new RakamException("User is not allowed to register clusters", UNAUTHORIZED);
        }

        try (Handle handle = dbi.open()) {
            try {
                handle.createStatement("INSERT INTO rakam_cluster (user_id, api_url, lock_key) VALUES (:userId, :apiUrl, :lockKey)")
                        .bind("userId", userId)
                        .bind("apiUrl", cluster.apiUrl)
                        .bind("lockKey", cluster.lockKey).execute();
            }
            catch (Throwable e) {
                int execute = handle.createStatement("UPDATE rakam_cluster SET lock_key = :lock_key WHERE user_id = :userId AND api_url = :apiUrl")
                        .bind("userId", userId)
                        .bind("apiUrl", cluster.apiUrl)
                        .bind("lockKey", cluster.lockKey).execute();

                if (execute == 0) {
                    throw new IllegalStateException();
                }

                return SuccessMessage.success("Lock key is updated");
            }

            return SuccessMessage.success();
        }
    }

    @JsonRequest
    @ApiOperation(value = "Delete cluster", authorizations = @Authorization(value = "read_key"))
    @Path("/get")
    public SuccessMessage delete(@CookieParam("session") String session,
            @ApiParam("api_url") String apiUrl)
    {
        int id = extractUserFromCookie(session, encryptionConfig.getSecretKey());

        try (Handle handle = dbi.open()) {
            handle.createStatement("DELETE FROM rakam_cluster WHERE (user_id, api_url) VALUES (:userId, :apiUrl)")
                    .bind("userId", id)
                    .bind("apiUrl", apiUrl).execute();
            return SuccessMessage.success();
        }
    }

    @JsonRequest
    @ApiOperation(value = "List cluster", authorizations = @Authorization(value = "read_key"))
    @Path("/list")
    @GET
    public List<String> list(@CookieParam("session") String session)
    {
        int id = extractUserFromCookie(session, encryptionConfig.getSecretKey());

        try (Handle handle = dbi.open()) {
            return handle.createQuery("SELECT api_url FROM rakam_cluster WHERE user_id = :userId")
                    .bind("userId", id).map(StringMapper.FIRST).list();
        }
    }

    public static class Cluster
    {
        public final String apiUrl;
        public final String lockKey;

        @JsonCreator
        public Cluster(@ApiParam("api_url") String apiUrl, @ApiParam(value = "lock_key", required = false) String lockKey)
        {
            this.apiUrl = apiUrl;
            this.lockKey = lockKey;
        }
    }
}
