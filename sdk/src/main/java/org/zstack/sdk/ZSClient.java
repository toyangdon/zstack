package org.zstack.sdk;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import okhttp3.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by xing5 on 2016/12/9.
 */
public class ZSClient {
    private static final OkHttpClient http = new OkHttpClient();

    static final Gson gson;
    static final Gson prettyGson;

    static {
        gson = new GsonBuilder().create();
        prettyGson = new GsonBuilder().setPrettyPrinting().create();
    }

    private static ZSConfig config;

    public static ZSConfig getConfig() {
        return config;
    }

    public static void setConfig(ZSConfig c) {
        config = c;
    }

    static class Api {
        AbstractAction action;
        RestInfo info;
        Completion completion;

        Api(AbstractAction action) {
            this.action = action;
            info = action.getRestInfo();
        }

        private String substituteUrl(String url, Map<String, Object> tokens) {
            Pattern pattern = Pattern.compile("\\{(.+?)\\}");
            Matcher matcher = pattern.matcher(url);
            StringBuffer buffer = new StringBuffer();
            while (matcher.find()) {
                String varName = matcher.group(1);
                Object replacement = tokens.get(varName);
                if (replacement == null) {
                    throw new ApiException(String.format("cannot find value for URL variable[%s]", varName));
                }

                matcher.appendReplacement(buffer, "");
                buffer.append(replacement.toString());
            }

            matcher.appendTail(buffer);
            return buffer.toString();
        }

        private List<String> getVarNamesFromUrl(String url) {
            Pattern pattern = Pattern.compile("\\{(.+?)\\}");
            Matcher matcher = pattern.matcher(url);

            List<String> urlVars = new ArrayList<>();
            while (matcher.find()) {
                urlVars.add(matcher.group(1));
            }

            return urlVars;
        }

        void call(Completion completion) {
            this.completion = completion;
            doCall();
        }

        ApiResult doCall() {
            action.checkParameters();

            HttpUrl url = new HttpUrl.Builder()
                    .scheme("http")
                    .host(config.getHostname())
                    .port(config.getPort())
                    .addPathSegment("/v1")
                    .addPathSegment(info.path)
                    .build();

            String urlstr;
            List<String> varNames = getVarNamesFromUrl(url.toString());
            if (!varNames.isEmpty()) {
                Map<String, Object> vars = new HashMap<>();
                for (String vname : varNames) {
                    Object value = action.getParameterValue(vname);

                    if (value == null) {
                        throw new ApiException(String.format("missing required field[%s]", vname));
                    }

                    vars.put(vname, value);
                }

                urlstr = substituteUrl(url.toString(), vars);
            } else {
                urlstr = url.toString();
            }

            Map<String, Object> body = new HashMap<>();
            for (String pname : action.getAllParameterNames()) {
                if (varNames.contains(pname) || Constants.SESSION_ID.equals(pname)) {
                    // the field is set in URL variables
                    continue;
                }

                Object value = action.getParameterValue(pname);
                if (value != null) {
                    body.put(pname, value);
                }
            }

            Request.Builder reqBuilder = new Request.Builder();
            reqBuilder.url(urlstr)
                    .method(info.httpMethod, RequestBody.create(Constants.JSON, gson.toJson(body)));

            if (info.needSession) {
                Object sessionId = action.getParameterValue(Constants.SESSION_ID);
                reqBuilder.addHeader(Constants.HEADER_AUTHORIZATION, String.format("%s %s", Constants.OAUTH, sessionId));
            }

            Request request = reqBuilder.build();

            try {
                Response response = http.newCall(request).execute();
                if (!response.isSuccessful()) {
                    return httpError(response.code(), response.body().toString());
                }

                if (response.code() == 200 || response.code() == 204) {
                    return writeApiResult(response);
                } else if (response.code() == 201) {
                    return pollResult(response);
                } else {
                    throw new ApiException(String.format("[Internal Error] the server returns an unknown status code[%s]", response.code()));
                }
            } catch (IOException e) {
                throw new ApiException(e);
            }
        }

        private ApiResult pollResult(Response response) {
            if (!info.needPoll) {
                throw new ApiException(String.format("[Internal Error] the api[%s] is not an async API but" +
                        " the server returns 201 status code", action.getClass().getSimpleName()));
            }

            Map body = gson.fromJson(response.body().toString(), LinkedHashMap.class);
            String pollingUrl = (String) body.get(Constants.LOCATION);
            if (pollingUrl == null) {
                throw new ApiException(String.format("Internal Error] the api[%s] is an async API but the server" +
                        " doesn't return the polling location url", action.getClass().getSimpleName()));
            }

            if (completion == null) {
                // sync polling
                return syncPollResult(pollingUrl);
            } else {
                // async polling
                asyncPollResult(response);
                return null;
            }
        }

        private void asyncPollResult(Response response) {
        }

        private ApiResult syncPollResult(String url) {
            long current = System.currentTimeMillis();
            Long timeout = (Long)action.getParameterValue("timeout");
            long expiredTime = current + (timeout == null ? TimeUnit.HOURS.toMillis(3) : timeout);
            Long interval = (Long) action.getParameterValue("pollingInterval");
            interval = interval == null ? TimeUnit.SECONDS.toMillis(5) : interval;

            Object sessionId = action.getParameterValue(Constants.SESSION_ID);

            while (current < expiredTime) {
                Request req = new Request.Builder()
                        .url(url)
                        .addHeader(Constants.HEADER_AUTHORIZATION, String.format("%s %s", Constants.OAUTH, sessionId))
                        .get()
                        .build();

                try {
                    Response response = http.newCall(req).execute();
                    if (!response.isSuccessful()) {
                        return httpError(response.code(), response.body().toString());
                    }

                    if (response.code() == 204) {
                        throw new ApiException(String.format("[Internal Error] the server returns 204 code for result polling" +
                                " of the api[%s]", action.getClass().getSimpleName()));
                    }

                    Map rsp = gson.fromJson(response.body().toString(), LinkedHashMap.class);
                    String state = (String) rsp.get("state");
                    if ("done".equals(state)) {
                        return writeApiResult(response);
                    }

                    TimeUnit.MILLISECONDS.sleep(interval);
                    current += interval;
                } catch (InterruptedException e) {
                    //ignore
                } catch (IOException e) {
                    throw new ApiException(e);
                }
            }

            ApiResult res = new ApiResult();
            res.error = new ErrorCode(
                    Constants.POLLING_TIMEOUT_ERROR,
                    "timeout of polling async API result",
                    String.format("polling result of api[%s] timeout after %s ms", action.getClass().getSimpleName(), timeout)
            );

            return res;
        }

        private ApiResult writeApiResult(Response response) {
            ApiResult res = new ApiResult();
            if (response.body().toString().isEmpty()) {
                // no body
                return res;
            }

            //TODO

            return null;
        }

        private ApiResult httpError(int code, String details) {
            ApiResult res = new ApiResult();
            res.error = new ErrorCode(
                    Constants.HTTP_ERROR,
                    String.format("the http status code[%s] indicates a failure happened", code),
                    details
            );
            return res;
        }

        ApiResult call() {
            return doCall();
        }
    }

    private static void errorIfNotConfigured() {
        if (config == null) {
            throw new RuntimeException("setConfig() must be called before any methods");
        }
    }

    static void call(AbstractAction action, Completion completion) {
        errorIfNotConfigured();
        new Api(action).call(completion);
    }

    static ApiResult call(AbstractAction action) {
        errorIfNotConfigured();
        return new Api(action).call();
    }
}
