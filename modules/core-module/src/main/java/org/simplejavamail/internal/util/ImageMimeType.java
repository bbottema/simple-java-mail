package org.simplejavamail.internal.util;

import jakarta.activation.MimetypesFileTypeMap;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static java.nio.charset.StandardCharsets.UTF_8;

class ImageMimeType {

    private static final String MIMETYPES = "image/xpm xpm\n"
            + "image/xbm xbm\n"
            + "image/x-xwindowdump xwd\n"
            + "image/x-xwd xwd\n"
            + "image/x-xpixmap xpm pm\n"
            + "image/x-xbm xbm\n"
            + "image/x-xbitmap xbm\n"
            + "image/x-windows-bmp bmp\n"
            + "image/x-tiff tif tiff\n"
            + "image/x-rgb rgb\n"
            + "image/x-quicktime qtif qti qif\n"
            + "image/x-portable-pixmap ppm\n"
            + "image/x-portable-greymap pgm\n"
            + "image/x-portable-graymap pgm\n"
            + "image/x-portable-bitmap pbm\n"
            + "image/x-portable-anymap pnm\n"
            + "image/x-pict pct\n"
            + "image/x-pcx pcx\n"
            + "image/x-niff niff nif\n"
            + "image/x-jps jps\n"
            + "image/x-jg art\n"
            + "image/x-icon ico\n"
            + "image/x-generic jpg tif wmf tiff bmp xpm png jpeg emf heic webp\n"
            + "image/x-eps eps\n"
            + "image/x-dwg svf dxf dwg\n"
            + "image/x-cmu-raster ras\n"
            + "image/vnd.xiff xif\n"
            + "image/vnd.wap.wbmp wbmp\n"
            + "image/vnd.rn-realpix rp\n"
            + "image/vnd.rn-realflash rf\n"
            + "image/vnd.net-fpx fpx\n"
            + "image/vnd.fpx fpx\n"
            + "image/vnd.dwg svf dxf dwg\n"
            + "image/vasa mcf\n"
            + "image/tiff tif tiff\n"
            + "image/svg+xml svgz svg\n"
            + "image/png x-png png PNG\n"
//            + "image/heic heic\n"
            + "image/pjpeg jpg jfif jpeg jpe\n"
            + "image/jpeg jpg JPG jfif jpeg jfif-tbnl jpe\n"
            + "image/gif gif GIF\n"
            + "image/bmp bmp bm\n"
            + "image/webp webp\n"
            + "image/pict pic pict\n"
            + "image/naplps naplps nap\n"
            + "image/jutvision jut\n"
            + "image/ief iefs ief\n"
            + "image/g3fax g3\n"
            + "image/florian flo turbot\n"
            + "image/fif fif\n"
            + "image/cmu-raster ras rast";

    public static final MimetypesFileTypeMap IMAGE_MIMETYPES_FILE_TYPE_MAP = createMap();

    /**
     * @return a vastly improved mimetype map
     */
    private static MimetypesFileTypeMap createMap() {
        try (InputStream is = new ByteArrayInputStream(MIMETYPES.getBytes(UTF_8))) {
            return new MimetypesFileTypeMap(is);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    public static String getContentType(String fileName) {
        return getContentType(fileName, null);
    }

    public static String getContentType(String fileName, String charset) {
        String mimeType = IMAGE_MIMETYPES_FILE_TYPE_MAP.getContentType(fileName.toLowerCase());
        if (charset != null && (mimeType.startsWith("text/") || mimeType.contains("javascript"))) {
            mimeType += ";charset=" + charset.toLowerCase();
        }
        return mimeType;
    }
}