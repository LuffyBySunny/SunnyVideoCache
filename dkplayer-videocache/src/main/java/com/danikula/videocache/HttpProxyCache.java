package com.danikula.videocache;

import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import com.danikula.videocache.file.FileCache;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Locale;

import static com.danikula.videocache.ProxyCacheUtils.DEFAULT_BUFFER_SIZE;
import static com.danikula.videocache.ProxyCacheUtils.decode;

/**
 * {@link ProxyCache} that read http url and writes data to {@link Socket}
 *
 * @author Alexey Danilov (danikula@gmail.com).
 */
class HttpProxyCache extends ProxyCache {

    private static final float NO_CACHE_BARRIER = .2f;

    private final HttpUrlSource source;
    private final FileCache cache;
    private CacheListener listener;

    public HttpProxyCache(HttpUrlSource source, FileCache cache) {
        super(source, cache);
        this.cache = cache;
        this.source = source;
    }

    public void registerCacheListener(CacheListener cacheListener) {
        this.listener = cacheListener;
    }

    /**
     * 处理请求，预加载和真正请求的时候都会走
     * @param request
     * @param socket
     * @throws IOException
     * @throws ProxyCacheException
     */
    public void processRequest(GetRequest request, Socket socket) throws IOException, ProxyCacheException {
        OutputStream out = new BufferedOutputStream(socket.getOutputStream());
        String responseHeaders = newResponseHeaders(request);
        out.write(responseHeaders.getBytes("UTF-8"));

        long offset = request.rangeOffset;
        if (isUseCache(request)) {
            responseWithCache(out, offset);
        } else {
            responseWithoutCache(out, offset);
        }
    }

    /**
     * 处理请求，预加载和真正请求的时候都会走
     * @param request
     * @param socket
     * @throws IOException
     * @throws ProxyCacheException
     */
    public void processRequest1(GetRequest request, Socket socket) throws IOException, ProxyCacheException {
        OutputStream out = new BufferedOutputStream(socket.getOutputStream());
        String responseHeaders = newResponseHeaders(request);
        out.write(responseHeaders.getBytes("UTF-8"));
        Uri uri = Uri.parse(request.uri);
        long offset = request.rangeOffset;
        // 如果是预加载
        if ("1".equals(uri.getQueryParameter("a"))) {
            if (isUseCache(request)) {
                responseWithCache(out, offset);
            }
        } else {
            if (isUseCache(request)) {
                responseMixCache(out, offset);
            } else {
                Log.d("DKPlayer", "不使用缓存");
                // 直接写回socket，而不写入文件，应该想想怎么写入文件，对整个视频文件进行缓存
                responseWithoutCache(out, offset);
            }
        }

    }


    /**
     *
     * @param request
     * @return
     * @throws ProxyCacheException
     */
    private boolean isUseCache(GetRequest request) throws ProxyCacheException {
        long sourceLength = source.length(); // 源文件长度
        boolean sourceLengthKnown = sourceLength > 0; // 源文件长度已知
        long cacheAvailable = cache.available();
        // do not use cache for partial requests which too far from available cache. It seems user seek video.
        // 当offset < 0 时request.partial  为false
        // 当 offset 的长度小于 已经缓存的 长度
        return !sourceLengthKnown || !request.partial || request.rangeOffset <= cacheAvailable + sourceLength * NO_CACHE_BARRIER;
    }

    private String newResponseHeaders(GetRequest request) throws IOException, ProxyCacheException {
        String mime = source.getMime();
        boolean mimeKnown = !TextUtils.isEmpty(mime);
        long length = cache.isCompleted() ? cache.available() : source.length();
        boolean lengthKnown = length >= 0;
        long contentLength = request.partial ? length - request.rangeOffset : length;
        boolean addRange = lengthKnown && request.partial;
        return new StringBuilder()
                .append(request.partial ? "HTTP/1.1 206 PARTIAL CONTENT\n" : "HTTP/1.1 200 OK\n")
                .append("Accept-Ranges: bytes\n")
                .append(lengthKnown ? format("Content-Length: %d\n", contentLength) : "")
                .append(addRange ? format("Content-Range: bytes %d-%d/%d\n", request.rangeOffset, length - 1, length) : "")
                .append(mimeKnown ? format("Content-Type: %s\n", mime) : "")
                .append("\n") // headers end
                .toString();
    }


    private void responseMixCache(OutputStream out, long offset) throws ProxyCacheException, IOException{
        byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
        int readBytes;

        if (cache.available() > offset + buffer.length) {
            Log.d("DKPlayer", "开始读取本地文件");
            while ((readBytes =  cache.read(buffer, offset, buffer.length)) != -1) {
                out.write(buffer, 0, readBytes);
                offset += readBytes;
            }
            // 当本地文件读完之后，继续从网络读取
        }
        Log.d("DKPlayer", "本地文件读取完毕");
        responseWithoutCache(out, offset);
        out.flush();
    }
    private void responseWithCache(OutputStream out, long offset) throws ProxyCacheException, IOException {
        byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
        int readBytes;
        // 无限循环
        while ((readBytes = read(buffer, offset, buffer.length)) != -1) {
            out.write(buffer, 0, readBytes);
            offset += readBytes;
        }
        out.flush();
    }



    private void responseWithoutCache(OutputStream out, long offset) throws ProxyCacheException, IOException {
        HttpUrlSource newSourceNoCache = new HttpUrlSource(this.source);
        try {
            newSourceNoCache.open((int) offset);
            byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
            int readBytes;
            while ((readBytes = newSourceNoCache.read(buffer)) != -1) {
                out.write(buffer, 0, readBytes);
                offset += readBytes;
            }
            out.flush();
        } finally {
            newSourceNoCache.close();
        }
    }

    private String format(String pattern, Object... args) {
        return String.format(Locale.US, pattern, args);
    }

    @Override
    protected void onCachePercentsAvailableChanged(int percents) {
        if (listener != null) {
            listener.onCacheAvailable(cache.file, source.getUrl(), percents);
        }
    }
}
