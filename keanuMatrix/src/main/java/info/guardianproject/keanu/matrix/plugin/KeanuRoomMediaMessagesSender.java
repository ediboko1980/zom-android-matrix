/*
 * Copyright 2016 OpenMarket Ltd
 * Copyright 2018 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package info.guardianproject.keanu.matrix.plugin;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.HandlerThread;
import android.os.Looper;
import android.support.media.ExifInterface;
import android.text.Html;
import android.text.TextUtils;
import android.util.Pair;

import org.matrix.androidsdk.MXDataHandler;
import org.matrix.androidsdk.crypto.MXEncryptedAttachments;
import org.matrix.androidsdk.data.Room;
import org.matrix.androidsdk.data.RoomMediaMessage;
import org.matrix.androidsdk.db.MXMediaCache;
import org.matrix.androidsdk.listeners.MXMediaUploadListener;
import org.matrix.androidsdk.rest.callback.ApiCallback;
import org.matrix.androidsdk.rest.model.Event;
import org.matrix.androidsdk.rest.model.MatrixError;
import org.matrix.androidsdk.rest.model.message.AudioMessage;
import org.matrix.androidsdk.rest.model.message.FileInfo;
import org.matrix.androidsdk.rest.model.message.FileMessage;
import org.matrix.androidsdk.rest.model.message.ImageInfo;
import org.matrix.androidsdk.rest.model.message.ImageMessage;
import org.matrix.androidsdk.rest.model.message.MediaMessage;
import org.matrix.androidsdk.rest.model.message.Message;
import org.matrix.androidsdk.rest.model.message.RelatesTo;
import org.matrix.androidsdk.rest.model.message.ThumbnailInfo;
import org.matrix.androidsdk.rest.model.message.VideoInfo;
import org.matrix.androidsdk.rest.model.message.VideoMessage;
import org.matrix.androidsdk.util.ImageUtils;
import org.matrix.androidsdk.util.JsonUtils;
import org.matrix.androidsdk.util.Log;
import org.matrix.androidsdk.util.PermalinkUtils;
import org.matrix.androidsdk.util.ResourceUtils;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Pattern;

import info.guardianproject.iocipher.File;
import info.guardianproject.iocipher.FileInputStream;
import info.guardianproject.iocipher.FileOutputStream;
import info.guardianproject.keanu.matrix.R;
/**
 * Room helper to send media messages in the right order.
 */
class KeanuRoomMediaMessagesSender {
    private static final String LOG_TAG = KeanuRoomMediaMessagesSender.class.getSimpleName();

    // pending events list
    private final List<RoomMediaMessage> mPendingRoomMediaMessages = new ArrayList<>();

    // linked room
    private final Room mRoom;

    // data handler
    private final MXDataHandler mDataHandler;

    // linked context
    private final Context mContext;

    // the sending item
    private RoomMediaMessage mSendingRoomMediaMessage;

    // UI thread
    private static android.os.Handler mUiHandler = null;

    // events creation threads
    private static android.os.Handler mEventHandler = null;

    // encoding creation threads
    private static android.os.Handler mEncodingHandler = null;

    // Pattern to strip previous reply when replying to a message. It also matches multi lines previous reply, when for instance containing blockquote.
    private static Pattern sPreviousReplyPattern = Pattern.compile("^<mx-reply>.*</mx-reply>", Pattern.DOTALL);

    /**
     * Constructor
     *
     * @param context     the context
     * @param dataHandler the dataHanlder
     * @param room        the room
     */
    KeanuRoomMediaMessagesSender(Context context, MXDataHandler dataHandler, Room room) {
        mRoom = room;
        mContext = context.getApplicationContext();
        mDataHandler = dataHandler;

        if (null == mUiHandler) {
            mUiHandler = new android.os.Handler(Looper.getMainLooper());

            HandlerThread eventHandlerThread = new HandlerThread("RoomDataItemsSender_event", Thread.MIN_PRIORITY);
            eventHandlerThread.start();
            mEventHandler = new android.os.Handler(eventHandlerThread.getLooper());

            HandlerThread encodingHandlerThread = new HandlerThread("RoomDataItemsSender_encoding", Thread.MIN_PRIORITY);
            encodingHandlerThread.start();
            mEncodingHandler = new android.os.Handler(encodingHandlerThread.getLooper());
        }
    }

    /**
     * Send a new media message to the room
     *
     * @param roomMediaMessage the message to send
     */
    void send(final RoomMediaMessage roomMediaMessage, RoomMediaMessage.EventCreationListener listener) {
        mEventHandler.post(new Runnable() {
            @Override
            public void run() {
                if (null == roomMediaMessage.getEvent()) {
                    Message message;
                    String mimeType = roomMediaMessage.getMimeType(mContext);

                    // avoid null case
                    if (null == mimeType) {
                        mimeType = "";
                    }

                    if (null == roomMediaMessage.getUri()) {
                        message = buildTextMessage(roomMediaMessage);
                    } else if (mimeType.startsWith("image/")) {
                        message = buildImageMessage(roomMediaMessage);
                    } else if (mimeType.startsWith("video/")) {
                        message = buildVideoMessage(roomMediaMessage);
                    } else {
                        message = buildFileMessage(roomMediaMessage);
                    }

                    if (null == message) {
                        Log.e(LOG_TAG, "## send " + roomMediaMessage + " not supported");


                        mUiHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                listener.onEventCreationFailed(roomMediaMessage,"not supported " + roomMediaMessage);
                            }
                        });
                        return;
                    }

                    roomMediaMessage.setMessageType(message.msgtype);

                    if (roomMediaMessage.getReplyToEvent() != null) {
                        // Note: it is placed here, but may be moved to the outer event during the encryption of the content
                        message.relatesTo = new RelatesTo();
                        message.relatesTo.dict = new HashMap<>();
                        message.relatesTo.dict.put("event_id", roomMediaMessage.getReplyToEvent().eventId);
                    }

                    Event event = new Event(message, mDataHandler.getUserId(), mRoom.getRoomId());

                    roomMediaMessage.setEvent(event);
                }

                mDataHandler.updateEventState(roomMediaMessage.getEvent(), Event.SentState.UNSENT);
                mRoom.storeOutgoingEvent(roomMediaMessage.getEvent());
                mDataHandler.getStore().commit();

                mUiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        listener.onEventCreated(roomMediaMessage);
                    }
                });

                synchronized (LOG_TAG) {
                    if (!mPendingRoomMediaMessages.contains(roomMediaMessage)) {
                        mPendingRoomMediaMessages.add(roomMediaMessage);
                    }
                }

                mUiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        // send the item
                        sendNext();
                    }
                });
            }
        });
    }

    /**
     * Skip the sending media item.
     */
    private void skip() {
        synchronized (LOG_TAG) {
            mSendingRoomMediaMessage = null;
        }

        sendNext();
    }

    /**
     * Send the next pending item
     */
    private void sendNext() {
        RoomMediaMessage roomMediaMessage;

        synchronized (LOG_TAG) {
            // please wait
            if (null != mSendingRoomMediaMessage) {
                return;
            }

            if (!mPendingRoomMediaMessages.isEmpty()) {
                mSendingRoomMediaMessage = mPendingRoomMediaMessages.get(0);
                mPendingRoomMediaMessages.remove(0);
            } else {
                // nothing to do
                return;
            }

            roomMediaMessage = mSendingRoomMediaMessage;
        }

        // upload the media first
        if (uploadMedia(roomMediaMessage)) {
            return;
        }

        // send the event
        sendEvent(roomMediaMessage.getEvent());
    }

    /**
     * Send the event after uploading the media
     *
     * @param event the event to send
     */
    private void sendEvent(final Event event) {
        mUiHandler.post(new Runnable() {
            @Override
            public void run() {
                // nothing more to upload
                mRoom.sendEvent(event, new ApiCallback<Void>() {
                    private ApiCallback<Void> getCallback() {
                        ApiCallback<Void> callback;

                        synchronized (LOG_TAG) {
                            callback = mSendingRoomMediaMessage.getSendingCallback();
                            mSendingRoomMediaMessage.setEventSendingCallback(null);
                            mSendingRoomMediaMessage = null;
                        }

                        return callback;
                    }

                    @Override
                    public void onSuccess(Void info) {
                        ApiCallback<Void> callback = getCallback();

                        if (null != callback) {
                            try {
                                callback.onSuccess(null);
                            } catch (Exception e) {
                                Log.e(LOG_TAG, "## sendNext() failed " + e.getMessage(), e);
                            }
                        }

                        sendNext();
                    }

                    @Override
                    public void onNetworkError(Exception e) {
                        ApiCallback<Void> callback = getCallback();

                        if (null != callback) {
                            try {
                                callback.onNetworkError(e);
                            } catch (Exception e2) {
                                Log.e(LOG_TAG, "## sendNext() failed " + e2.getMessage(), e2);
                            }
                        }

                        sendNext();
                    }

                    @Override
                    public void onMatrixError(MatrixError e) {
                        ApiCallback<Void> callback = getCallback();

                        if (null != callback) {
                            try {
                                callback.onMatrixError(e);
                            } catch (Exception e2) {
                                Log.e(LOG_TAG, "## sendNext() failed " + e2.getMessage(), e2);
                            }
                        }

                        sendNext();
                    }

                    @Override
                    public void onUnexpectedError(Exception e) {
                        ApiCallback<Void> callback = getCallback();

                        if (null != callback) {
                            try {
                                callback.onUnexpectedError(e);
                            } catch (Exception e2) {
                                Log.e(LOG_TAG, "## sendNext() failed " + e2.getMessage(), e2);
                            }
                        }

                        sendNext();
                    }
                });
            }
        });
    }

    //==============================================================================================================
    // Messages builder methods.
    //==============================================================================================================

    /**
     * Build a text message from a RoomMediaMessage.
     *
     * @param roomMediaMessage the RoomMediaMessage.
     * @return the message
     */
    private Message buildTextMessage(RoomMediaMessage roomMediaMessage) {
        CharSequence sequence = roomMediaMessage.getText();
        String htmlText = roomMediaMessage.getHtmlText();
        String text = null;

        if (null == sequence) {
            if (null != htmlText) {
                text = Html.fromHtml(htmlText).toString();
            }
        } else {
            text = sequence.toString();
        }

        // a text message cannot be null
        if (TextUtils.isEmpty(text) && !TextUtils.equals(roomMediaMessage.getMessageType(), Message.MSGTYPE_EMOTE)) {
            return null;
        }

        Message message = new Message();
        message.msgtype = (null == roomMediaMessage.getMessageType()) ? Message.MSGTYPE_TEXT : roomMediaMessage.getMessageType();
        message.body = text;

        // an emote can have an empty body
        if (null == message.body) {
            message.body = "";
        }

        if (!TextUtils.isEmpty(htmlText)) {
            message.formatted_body = htmlText;
            message.format = Message.FORMAT_MATRIX_HTML;
        }

        // Deals with in reply to event
        Event replyToEvent = roomMediaMessage.getReplyToEvent();
        if (replyToEvent != null) {
            // Cf. https://docs.google.com/document/d/1BPd4lBrooZrWe_3s_lHw_e-Dydvc7bXbm02_sV2k6Sc
            String msgType = JsonUtils.getMessageMsgType(replyToEvent.getContentAsJsonObject());

            // Build body and formatted body, depending of the `msgtype` of the event the user is replying to
            if (msgType != null) {
                // Compute the content of the event user is replying to
                String replyToBody;
                String replyToFormattedBody;
                boolean replyToEventIsAlreadyAReply = false;

                switch (msgType) {
                    case Message.MSGTYPE_TEXT:
                    case Message.MSGTYPE_NOTICE:
                    case Message.MSGTYPE_EMOTE:
                        Message messageToReplyTo = JsonUtils.toMessage(replyToEvent.getContentAsJsonObject());

                        replyToBody = messageToReplyTo.body;

                        if (TextUtils.isEmpty(messageToReplyTo.formatted_body)) {
                            replyToFormattedBody = messageToReplyTo.body;
                        } else {
                            replyToFormattedBody = messageToReplyTo.formatted_body;
                        }

                        replyToEventIsAlreadyAReply = messageToReplyTo.relatesTo != null
                                && messageToReplyTo.relatesTo.dict != null
                                && !TextUtils.isEmpty(messageToReplyTo.relatesTo.dict.get("event_id"));

                        break;
                    case Message.MSGTYPE_IMAGE:
                        replyToBody = mContext.getString(R.string.reply_to_an_image);
                        replyToFormattedBody = replyToBody;
                        break;
                    case Message.MSGTYPE_VIDEO:
                        replyToBody = mContext.getString(R.string.reply_to_a_video);
                        replyToFormattedBody = replyToBody;
                        break;
                    case Message.MSGTYPE_AUDIO:
                        replyToBody = mContext.getString(R.string.reply_to_an_audio_file);
                        replyToFormattedBody = replyToBody;
                        break;
                    case Message.MSGTYPE_FILE:
                        replyToBody = mContext.getString(R.string.reply_to_a_file);
                        replyToFormattedBody = replyToBody;
                        break;
                    default:
                        // Other msg types are not supported yet
                        Log.w(LOG_TAG, "Reply to: unsupported msgtype: " + msgType);
                        replyToBody = null;
                        replyToFormattedBody = null;
                        break;
                }

                if (replyToBody != null) {
                    String replyContent;
                    if (TextUtils.isEmpty(message.formatted_body)) {
                        replyContent = message.body;
                    } else {
                        replyContent = message.formatted_body;
                    }

                    message.body = includeReplyToToBody(replyToEvent,
                            replyToBody,
                            replyToEventIsAlreadyAReply,
                            message.body,
                            msgType.equals(Message.MSGTYPE_EMOTE));
                    message.formatted_body = includeReplyToToFormattedBody(replyToEvent,
                            replyToFormattedBody,
                            replyToEventIsAlreadyAReply,
                            replyContent,
                            msgType.equals(Message.MSGTYPE_EMOTE));

                    // Note: we need to force the format to Message.FORMAT_MATRIX_HTML
                    message.format = Message.FORMAT_MATRIX_HTML;
                } else {
                    Log.e(LOG_TAG, "Unsupported 'msgtype': " + msgType + ". Consider calling Room.canReplyTo(Event)");

                    // Ensure there will not be "m.relates_to" data in the sent event
                    roomMediaMessage.setReplyToEvent(null);
                }
            } else {
                Log.e(LOG_TAG, "Null 'msgtype'. Consider calling Room.canReplyTo(Event)");

                // Ensure there will not be "m.relates_to" data in the sent event
                roomMediaMessage.setReplyToEvent(null);
            }
        }

        return message;
    }

    private String includeReplyToToBody(Event replyToEvent,
                                        String replyToBody,
                                        boolean stripPreviousReplyTo,
                                        String messageBody,
                                        boolean isEmote) {
        int firstLineIndex = 0;

        String[] lines = replyToBody.split("\n");

        if (stripPreviousReplyTo) {
            // Strip replyToBody from previous reply to

            // Strip line starting with "> "
            while (firstLineIndex < lines.length && lines[firstLineIndex].startsWith("> ")) {
                firstLineIndex++;
            }

            // Strip empty line after
            if (firstLineIndex < lines.length && lines[firstLineIndex].isEmpty()) {
                firstLineIndex++;
            }
        }

        StringBuilder ret = new StringBuilder();

        if (firstLineIndex < lines.length) {
            // Add <${mxid}> to the first line
            if (isEmote) {
                lines[firstLineIndex] = "* <" + replyToEvent.sender + "> " + lines[firstLineIndex];
            } else {
                lines[firstLineIndex] = "<" + replyToEvent.sender + "> " + lines[firstLineIndex];
            }

            for (int i = firstLineIndex; i < lines.length; i++) {
                ret.append("> ")
                        .append(lines[i])
                        .append("\n");
            }
        }

        ret.append("\n")
                .append(messageBody);

        return ret.toString();
    }

    private String includeReplyToToFormattedBody(Event replyToEvent,
                                                 String replyToFormattedBody,
                                                 boolean stripPreviousReplyTo,
                                                 String messageFormattedBody,
                                                 boolean isEmote) {
        if (stripPreviousReplyTo) {
            // Strip replyToFormattedBody from previous reply to
            replyToFormattedBody = sPreviousReplyPattern.matcher(replyToFormattedBody).replaceAll("");
        }

        StringBuilder ret = new StringBuilder("<mx-reply><blockquote><a href=\"")
                // ${evLink}
                .append(PermalinkUtils.createPermalink(replyToEvent))
                .append("\">")
                // "In reply to"
                .append(mContext.getString(R.string.message_reply_to_prefix))
                .append("</a> ");

        if (isEmote) {
            ret.append("* ");
        }

        ret.append("<a href=\"")
                // ${userLink}
                .append(PermalinkUtils.createPermalink(replyToEvent.sender))
                .append("\">")
                // ${mxid}
                .append(replyToEvent.sender)
                .append("</a><br>")
                .append(replyToFormattedBody)
                .append("</blockquote></mx-reply>")
                .append(messageFormattedBody);

        return ret.toString();
    }

    /**
     * Returns the thumbnail path of shot image.
     *
     * @param picturePath the image path
     * @return the thumbnail image path.
     */
    private static String getThumbnailPath(String picturePath) {
        if (!TextUtils.isEmpty(picturePath) && picturePath.endsWith(".jpg")) {
            return picturePath.replace(".jpg", "_thumb.jpg");
        }

        return null;
    }

    /**
     * Retrieves the image thumbnail saved by the media picker.
     *
     * @param sharedDataItem the sharedItem
     * @return the thumbnail if it exits.
     */
    private Bitmap getMediaPickerThumbnail(RoomMediaMessage sharedDataItem) {
        Bitmap thumbnailBitmap = null;

        try {
            String thumbPath = getThumbnailPath(sharedDataItem.getUri().getPath());

            if (null != thumbPath) {
                File thumbFile = new File(thumbPath);

                if (thumbFile.exists()) {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    thumbnailBitmap = BitmapFactory.decodeFile(thumbPath, options);
                }
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "cannot restore the media picker thumbnail " + e.getMessage(), e);
        } catch (OutOfMemoryError oom) {
            Log.e(LOG_TAG, "cannot restore the media picker thumbnail oom", oom);
        }

        return thumbnailBitmap;
    }

    /**
     * Retrieve the media Url.
     *
     * @param roomMediaMessage the room media message
     * @return the media URL
     */
    private String getMediaUrl(RoomMediaMessage roomMediaMessage) {
        String mediaUrl = roomMediaMessage.getUri().toString();

        if ((!mediaUrl.startsWith("file:"))
                && (!mediaUrl.startsWith("vfs:"))) {
            // save the content:// file in to the media cache
            String mimeType = roomMediaMessage.getMimeType(mContext);
            ResourceUtils.Resource resource = ResourceUtils.openResource(mContext, roomMediaMessage.getUri(), mimeType);

            // save the file in the filesystem
            mediaUrl = mDataHandler.getMediaCache().saveMedia(resource.mContentStream, null, mimeType);
            resource.close();
        }

        return mediaUrl;
    }

    /**
     * Build an image message from a RoomMediaMessage.
     *
     * @param roomMediaMessage the roomMediaMessage
     * @return the image message
     */
    private Message buildImageMessage(RoomMediaMessage roomMediaMessage) {
        try {
            String mimeType = roomMediaMessage.getMimeType(mContext);
            final MXMediaCache mediaCache = mDataHandler.getMediaCache();

            String mediaUrl = getMediaUrl(roomMediaMessage);
            String thumbnailURL = mediaUrl + ".thumb.jpg";

            /**
            // compute the thumbnail
            Bitmap thumbnailBitmap = roomMediaMessage.getFullScreenImageKindThumbnail(mContext);

            if (null == thumbnailBitmap) {
                thumbnailBitmap = getMediaPickerThumbnail(roomMediaMessage);
            }

            if (null == thumbnailBitmap) {
                Pair<Integer, Integer> thumbnailSize = roomMediaMessage.getThumbnailSize();
                thumbnailBitmap = ResourceUtils.createThumbnailBitmap(mContext, roomMediaMessage.getUri(), thumbnailSize.first, thumbnailSize.second);
            }

            if (null == thumbnailBitmap) {
                thumbnailBitmap = roomMediaMessage.getMiniKindImageThumbnail(mContext);
            }

            String thumbnailURL = null;

            if (null != thumbnailBitmap) {
                thumbnailURL = mediaCache.saveBitmap(thumbnailBitmap, null);
            }

            // get the exif rotation angle
            final int rotationAngle = ImageUtils.getRotationAngleForBitmap(mContext, Uri.parse(mediaUrl));

            if (0 != rotationAngle) {
                // always apply the rotation to the image
                ImageUtils.rotateImage(mContext, thumbnailURL, rotationAngle, mediaCache);
            }
        **/

            ImageMessage imageMessage = new ImageMessage();
            imageMessage.url = mediaUrl;
            imageMessage.body = roomMediaMessage.getFileName(mContext);
            if (imageMessage.body == null)
                imageMessage.body = Uri.parse(mediaUrl).getLastPathSegment();

            if (TextUtils.isEmpty(imageMessage.body)) {
                imageMessage.body = "Image";
            }

            Uri imageUri = Uri.parse(mediaUrl);

            if (null == imageMessage.info) {
                fillImageInfo(mContext, imageMessage, imageUri, mimeType);
            }


            if ((null != thumbnailURL) && (null != imageMessage.info) && (null == imageMessage.info.thumbnailInfo)) {
                Uri thumbUri = Uri.parse(thumbnailURL);
                ImageInfo info = fillThumbnailInfo(mContext, imageMessage, thumbUri, "image/jpeg");
                if (info != null)
                    imageMessage.info.thumbnailUrl = thumbnailURL;
            }

            return imageMessage;
        } catch (Exception e) {
            Log.e(LOG_TAG, "## buildImageMessage() failed " + e.getMessage(), e);
        }

        return null;
    }

    public static ImageInfo fillImageInfo(Context context, ImageMessage imageMessage, Uri imageUri, String mimeType) {
        imageMessage.info = getImageInfo(context, imageMessage.info, imageUri, mimeType);
        return imageMessage.info;
    }

    public static ImageInfo fillThumbnailInfo(Context context, ImageMessage imageMessage, Uri thumbUri, String mimeType) {
        ImageInfo imageInfo = getImageInfo(context, (ImageInfo)null, thumbUri, mimeType);
        if (null != imageInfo) {
            if (null == imageMessage.info) {
                imageMessage.info = new ImageInfo();
            }

            imageMessage.info.thumbnailInfo = new ThumbnailInfo();
            imageMessage.info.thumbnailInfo.w = imageInfo.w;
            imageMessage.info.thumbnailInfo.h = imageInfo.h;
            imageMessage.info.thumbnailInfo.size = imageInfo.size;
            imageMessage.info.thumbnailInfo.mimetype = imageInfo.mimetype;
        }
        return imageInfo;

    }

    public static ImageInfo getImageInfo(Context context, ImageInfo anImageInfo, Uri imageUri, String mimeType) {
        ImageInfo imageInfo = null == anImageInfo ? new ImageInfo() : anImageInfo;

        try {

            ExifInterface exifMedia = null;

            String filename = imageUri.getPath();
            java.io.File file = new java.io.File(filename);
            if (file.exists())
                exifMedia = new ExifInterface(filename);
            else
            {
                info.guardianproject.iocipher.File fileEncrypted = new info.guardianproject.iocipher.File(filename);
                if (fileEncrypted.exists())
                {
                    InputStream is = new info.guardianproject.iocipher.FileInputStream(fileEncrypted);
                    exifMedia = new ExifInterface(is);

                }
                else
                    return null;
            }



            String sWidth = exifMedia.getAttribute("ImageWidth");
            String sHeight = exifMedia.getAttribute("ImageLength");
            imageInfo.orientation = ImageUtils.getOrientationForBitmap(context, imageUri);
            int width = 0;
            int height = 0;
            if (null != sWidth && null != sHeight) {
                if (imageInfo.orientation != 5 && imageInfo.orientation != 6 && imageInfo.orientation != 7 && imageInfo.orientation != 8) {
                    width = Integer.parseInt(sWidth);
                    height = Integer.parseInt(sHeight);
                } else {
                    height = Integer.parseInt(sWidth);
                    width = Integer.parseInt(sHeight);
                }
            }

            if (0 == width || 0 == height) {
                try {
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(imageUri.getPath(), opts);
                    if (opts.outHeight > 0 && opts.outWidth > 0) {
                        width = opts.outWidth;
                        height = opts.outHeight;
                    }
                } catch (Exception var13) {
                    Log.e(LOG_TAG, "fillImageInfo : failed" + var13.getMessage(), var13);
                } catch (OutOfMemoryError var14) {
                    Log.e(LOG_TAG, "fillImageInfo : oom", var14);
                }
            }

            if (0 != width || 0 != height) {
                imageInfo.w = width;
                imageInfo.h = height;
            }

            imageInfo.mimetype = mimeType;
            imageInfo.size = file.length();
        } catch (Exception var15) {
            Log.e(LOG_TAG, "fillImageInfo : failed" + var15.getMessage(), var15);
            imageInfo = null;
        }

        return imageInfo;
    }

    /**
     * Compute the video thumbnail
     *
     * @param videoUrl the video url
     * @return the video thumbnail
     */
    public String getVideoThumbnailUrl(final String videoUrl) {
        String thumbUrl = videoUrl + ".thumb.jpg";
        /**
        try {
            Uri uri = Uri.parse(videoUrl);
            //Bitmap thumb = createVideoThumbnail(uri, MediaStore.Images.Thumbnails.MINI_KIND);
            Bitmap thumb = new BitmapDrawable(mContext.getDrawable(android.R.d))
            thumbUrl = mDataHandler.getMediaCache().saveBitmap(thumb, null);
        } catch (Exception e) {
            Log.e(LOG_TAG, "## getVideoThumbnailUrl() failed with " + e.getMessage(), e);
        }**/

        return thumbUrl;
    }


    /**
     * Create a video thumbnail for a video. May return null if the video is
     * corrupt or the format is not supported.
     *
     * @param uriMedia the path of video file
     * @param kind could be MINI_KIND or MICRO_KIND
     */
    /**
    public static Bitmap createVideoThumbnail(Uri uriMedia, int kind) {
        Bitmap bitmap = null;

        if (uriMedia.getScheme().equals("vfs")) {
            int frameNumber = 42;
            Picture picture = null;
            try {
                IOCipherFileChannel fc = new info.guardianproject.iocipher.FileInputStream(uriMedia.getPath()).getChannel();
                picture = FrameGrab.getFrameFromChannel(new IOCipherFileChannelWrapper(fc), frameNumber);

                //for Android (jcodec-android)
                bitmap = AndroidUtil.toBitmap(picture);

            } catch (JCodecException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        else
        {
            bitmap = ThumbnailUtils.createVideoThumbnail(uriMedia.getPath(),kind);
        }

        if (bitmap == null) return null;

        // Scale down the bitmap if it's too large.
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int max = Math.max(width, height);
        if (max > 512) {
            float scale = 512f / max;
            int w = Math.round(scale * width);
            int h = Math.round(scale * height);
            bitmap = Bitmap.createScaledBitmap(bitmap, w, h, true);
        }

        return bitmap;
    }**/

    /**
     * Build an video message from a RoomMediaMessage.
     *
     * @param roomMediaMessage the roomMediaMessage
     * @return the video message
     */
    private Message buildVideoMessage(RoomMediaMessage roomMediaMessage) {
        try {
            String mediaUrl = getMediaUrl(roomMediaMessage);
            String thumbnailUrl = getVideoThumbnailUrl(mediaUrl);

            VideoMessage videoMessage = new VideoMessage();
            videoMessage.url = mediaUrl;
            videoMessage.body = roomMediaMessage.getFileName(mContext);
            if (videoMessage.body == null)
                videoMessage.body = Uri.parse(mediaUrl).getLastPathSegment();

            Uri videoUri = Uri.parse(mediaUrl);
            Uri thumbnailUri = (null != thumbnailUrl) ? Uri.parse(thumbnailUrl) : null;

            fillVideoInfo(mContext, videoMessage, videoUri, roomMediaMessage.getMimeType(mContext), thumbnailUri, "image/jpeg");

            if (null == videoMessage.body) {
                videoMessage.body = videoUri.getLastPathSegment();
            }

            return videoMessage;
        } catch (Exception e) {
            Log.e(LOG_TAG, "## buildVideoMessage() failed " + e.getMessage(), e);
        }

        return null;
    }

    public static void fillVideoInfo(Context context, VideoMessage videoMessage, Uri fileUri, String videoMimeType, Uri thumbnailUri, String thumbMimeType) {
        try {

            VideoInfo videoInfo = new VideoInfo();

            /**
            File file = new File(fileUri.getPath());

            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            Bitmap bmp = retriever.getFrameAtTime();
            videoInfo.h = bmp.getHeight();
            videoInfo.w = bmp.getWidth();
             **/

            videoInfo.mimetype = videoMimeType;
            videoInfo.duration = 10L;

            /**
            try {
                MediaPlayer mp = MediaPlayer.create(context, fileUri);
                if (null != mp) {
                    videoInfo.duration = (long)mp.getDuration();
                    mp.release();
                }
            } catch (Exception var15) {
                Log.e(LOG_TAG, "fillVideoInfo : MediaPlayer.create failed" + var15.getMessage(), var15);
            }**/

            videoInfo.size = new info.guardianproject.iocipher.File(fileUri.getPath()).length();
            if (null != thumbnailUri) {
                videoInfo.thumbnail_url = thumbnailUri.toString();
                ThumbnailInfo thumbInfo = new ThumbnailInfo();

                thumbInfo.w = 1080;
                thumbInfo.h = 720;

                info.guardianproject.iocipher.File thumbnailFile = new info.guardianproject.iocipher.File(thumbnailUri.getPath());
                if (thumbnailFile.exists()) {
                    ExifInterface exifMedia = new ExifInterface(new info.guardianproject.iocipher.FileInputStream(thumbnailFile));
                    String sWidth = exifMedia.getAttribute("ImageWidth");
                    String sHeight = exifMedia.getAttribute("ImageLength");
                    if (null != sWidth) {
                        thumbInfo.w = Integer.parseInt(sWidth);
                    }

                    if (null != sHeight) {
                        thumbInfo.h = Integer.parseInt(sHeight);
                    }
                }

                videoInfo.h = thumbInfo.h;
                videoInfo.w = thumbInfo.w;
                thumbInfo.size = thumbnailFile.length();
                thumbInfo.mimetype = thumbMimeType;
                videoInfo.thumbnail_info = thumbInfo;
            }

            videoMessage.info = videoInfo;

        } catch (Exception var16) {
            Log.e(LOG_TAG, "fillVideoInfo : failed" + var16.getMessage(), var16);
        }

    }

    /**
     * Build an file message from a RoomMediaMessage.
     *
     * @param roomMediaMessage the roomMediaMessage
     * @return the video message
     */
    private Message buildFileMessage(RoomMediaMessage roomMediaMessage) {
        try {
            String mimeType = roomMediaMessage.getMimeType(mContext);

            String mediaUrl = getMediaUrl(roomMediaMessage);
            FileMessage fileMessage;

            if (mimeType.startsWith("audio/")) {
                fileMessage = new AudioMessage();
            } else {
                fileMessage = new FileMessage();
            }

            fileMessage.url = mediaUrl;
            fileMessage.body = roomMediaMessage.getFileName(mContext);
            if (fileMessage.body == null)
                fileMessage.body = Uri.parse(mediaUrl).getLastPathSegment();

            Uri uri = Uri.parse(mediaUrl);
            fillFileInfo(mContext, fileMessage, uri, mimeType);

            if (null == fileMessage.body) {
                fileMessage.body = uri.getLastPathSegment();
            }

            return fileMessage;
        } catch (Exception e) {
            Log.e(LOG_TAG, "## buildFileMessage() failed " + e.getMessage(), e);
        }

        return null;
    }

    public static void fillFileInfo(Context context, FileMessage fileMessage, Uri fileUri, String mimeType) {
        try {
            FileInfo fileInfo = new FileInfo();
            String filename = fileUri.getPath();
            File file = new File(filename);
            fileInfo.mimetype = mimeType;
            fileInfo.size = file.length();
            fileMessage.info = fileInfo;
        } catch (Exception var7) {
            Log.e(LOG_TAG, "fillFileInfo : failed" + var7.getMessage(), var7);
        }

    }

    //==============================================================================================================
    // Upload media management
    //==============================================================================================================

    /**
     * Upload the media.
     *
     * @param roomMediaMessage the roomMediaMessage
     * @return true if a media is uploaded
     */
    private boolean uploadMedia(final RoomMediaMessage roomMediaMessage) {
        final Event event = roomMediaMessage.getEvent();
        final Message message = JsonUtils.toMessage(event.getContent());

        if (!(message instanceof MediaMessage)) {
            return false;
        }

        final MediaMessage mediaMessage = (MediaMessage) message;
        final String url;
        final String fMimeType;

        if (mediaMessage.isThumbnailLocalContent()) {
            url = mediaMessage.getThumbnailUrl();
            fMimeType = "image/jpeg";
        } else if (mediaMessage.isLocalContent()||mediaMessage.getUrl().startsWith("vfs:")) {
            url = mediaMessage.getUrl();
            fMimeType = mediaMessage.getMimeType();
        } else {
            return false;
        }

        mEncodingHandler.post(() -> {
            final MXMediaCache mediaCache = mDataHandler.getMediaCache();

            Uri uri = Uri.parse(url);
            String mimeType = fMimeType;
            final MXEncryptedAttachments.EncryptionResult encryptionResult;
            final Uri encryptedUri;
            InputStream stream;

            String filename = null;

            try {
                stream = null;
                if (uri.getScheme() == null || uri.getScheme().equals("file"))
                    stream = new java.io.FileInputStream(new File(uri.getPath()));
                else if (uri.getScheme().equals("vfs"))
                    stream = new FileInputStream(new File(uri.getPath()));

                if (mRoom.isEncrypted() && mDataHandler.isCryptoEnabled() && (null != stream)) {
                    File fileEncrypted = new File(uri.getPath() + ".encrypted");
                    FileOutputStream fos = new FileOutputStream(fileEncrypted);
                    encryptionResult = KeanuMXEncryptedAttachments.encryptAttachment(stream, mimeType,fos);
                    stream.close();

                    if (null != encryptionResult) {
                        mimeType = "application/octet-stream";
                        encryptedUri = Uri.parse("vfs://" + fileEncrypted.getAbsolutePath());
                        stream = new FileInputStream(fileEncrypted);
                    } else {
                        skip();

                        mUiHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                mDataHandler.updateEventState(roomMediaMessage.getEvent(), Event.SentState.UNDELIVERED);
                                mRoom.storeOutgoingEvent(roomMediaMessage.getEvent());
                                mDataHandler.getStore().commit();

                               // roomMediaMessage.onEncryptionFailed();
                            }
                        });

                        return;
                    }
                } else {
                    // Only pass filename string to server in non-encrypted rooms to prevent leaking filename
                    filename = mediaMessage.isThumbnailLocalContent() ? ("thumb" + message.body) : message.body;
                    encryptionResult = null;
                    encryptedUri = null;
                }
            } catch (Exception e) {
                skip();
                return;
            }

            mDataHandler.updateEventState(roomMediaMessage.getEvent(), Event.SentState.SENDING);

            mediaCache.uploadContent(stream, filename, mimeType, url,
                    new MXMediaUploadListener() {
                        @Override
                        public void onUploadStart(final String uploadId) {
                            mUiHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (null != roomMediaMessage.getMediaUploadListener()) {
                                        roomMediaMessage.getMediaUploadListener().onUploadStart(uploadId);
                                    }
                                }
                            });
                        }

                        @Override
                        public void onUploadCancel(final String uploadId) {
                            mUiHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    mDataHandler.updateEventState(roomMediaMessage.getEvent(), Event.SentState.UNDELIVERED);

                                    if (null != roomMediaMessage.getMediaUploadListener()) {
                                        roomMediaMessage.getMediaUploadListener().onUploadCancel(uploadId);
                                        roomMediaMessage.setMediaUploadListener(null);
                                        roomMediaMessage.setEventSendingCallback(null);
                                    }

                                    skip();
                                }
                            });
                        }

                        @Override
                        public void onUploadError(final String uploadId, final int serverResponseCode, final String serverErrorMessage) {
                            mUiHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    mDataHandler.updateEventState(roomMediaMessage.getEvent(), Event.SentState.UNDELIVERED);

                                    if (null != roomMediaMessage.getMediaUploadListener()) {
                                        roomMediaMessage.getMediaUploadListener().onUploadError(uploadId, serverResponseCode, serverErrorMessage);
                                        roomMediaMessage.setMediaUploadListener(null);
                                        roomMediaMessage.setEventSendingCallback(null);
                                    }

                                    skip();
                                }
                            });
                        }

                        @Override
                        public void onUploadComplete(final String uploadId, final String contentUri) {
                            mUiHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    boolean isThumbnailUpload = mediaMessage.isThumbnailLocalContent();

                                    if (isThumbnailUpload) {
                                        mediaMessage.setThumbnailUrl(encryptionResult, contentUri);

                                        if (null != encryptionResult) {
                                            mediaCache.saveFileMediaForUrl(contentUri, encryptedUri.toString(), -1, -1, "image/jpeg");
                                            try {
                                                new File(Uri.parse(url).getPath()).delete();
                                            } catch (Exception e) {
                                                Log.e(LOG_TAG, "## cannot delete the uncompress media", e);
                                            }
                                        } else {
                                            Pair<Integer, Integer> thumbnailSize = roomMediaMessage.getThumbnailSize();
                                            mediaCache.saveFileMediaForUrl(contentUri, url, thumbnailSize.first, thumbnailSize.second, "image/jpeg");
                                        }

                                        // update the event content with the new message info
                                        event.updateContent(JsonUtils.toJson(message));

                                        // force to save the room events list
                                        // https://github.com/vector-im/riot-android/issues/1390
                                        mDataHandler.getStore().flushRoomEvents(mRoom.getRoomId());

                                        // upload the media
                                        uploadMedia(roomMediaMessage);
                                    } else {
                                        if (null != encryptedUri) {
                                            // replace the thumbnail and the media contents by the computed one
                                            mediaCache.saveFileMediaForUrl(contentUri, encryptedUri.toString(), mediaMessage.getMimeType());
                                            try {
                                                new File(Uri.parse(url).getPath()).delete();
                                            } catch (Exception e) {
                                                Log.e(LOG_TAG, "## cannot delete the uncompress media", e);
                                            }
                                        } else {
                                            // replace the thumbnail and the media contents by the computed one
                                            mediaCache.saveFileMediaForUrl(contentUri, url, mediaMessage.getMimeType());
                                        }
                                        mediaMessage.setUrl(encryptionResult, contentUri);

                                        // update the event content with the new message info
                                        event.updateContent(JsonUtils.toJson(message));

                                        // force to save the room events list
                                        // https://github.com/vector-im/riot-android/issues/1390
                                        mDataHandler.getStore().flushRoomEvents(mRoom.getRoomId());

                                        Log.d(LOG_TAG, "Uploaded to " + contentUri);

                                        // send
                                        sendEvent(event);
                                    }

                                    if (null != roomMediaMessage.getMediaUploadListener()) {
                                        roomMediaMessage.getMediaUploadListener().onUploadComplete(uploadId, contentUri);

                                        if (!isThumbnailUpload) {
                                            roomMediaMessage.setMediaUploadListener(null);
                                        }
                                    }
                                }
                            });
                        }
                    });
        });

        return true;
    }


}
