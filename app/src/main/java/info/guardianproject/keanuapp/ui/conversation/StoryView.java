package info.guardianproject.keanuapp.ui.conversation;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import androidx.core.app.ActivityCompat;
import androidx.loader.app.LoaderManager;
import androidx.loader.content.CursorLoader;
import androidx.loader.content.Loader;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SnapHelper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.github.barteksc.pdfviewer.PDFView;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.ui.PlayerControlView;
import com.google.android.exoplayer2.ui.SimpleExoPlayerView;

import info.guardianproject.keanu.core.provider.Imps;
import info.guardianproject.keanu.core.util.SecureMediaStore;
import info.guardianproject.keanuapp.R;
import info.guardianproject.keanuapp.ui.widgets.AudioRecorder;
import info.guardianproject.keanuapp.ui.widgets.CircularPulseImageButton;
import info.guardianproject.keanuapp.ui.widgets.MediaInfo;
import info.guardianproject.keanuapp.ui.widgets.MessageViewHolder;
import info.guardianproject.keanuapp.ui.widgets.PZSImageView;
import info.guardianproject.keanuapp.ui.widgets.PdfViewActivity;
import info.guardianproject.keanuapp.ui.widgets.StoryAudioPlayer;
import info.guardianproject.keanuapp.ui.widgets.StoryExoPlayerManager;

import static info.guardianproject.keanu.core.KeanuConstants.LOG_TAG;

/**
 * Created by N-Pex on 2019-03-28.
 */
public class StoryView extends ConversationView implements AudioRecorder.AudioRecorderListener {
    private final ProgressBar progressBar;
    private final SnapHelper snapHelper;
    private final SimpleExoPlayerView previewAudio;
    private final StoryAudioPlayer storyAudioPlayer;
    private final PlayerControlView storyAudioPlayerView;
    private AudioRecorder audioRecorder;
    private int currentPage = -1;
    private RecyclerView.ViewHolder currentPageViewHolder = null;

    private View mMicButton;

    private static final int AUTO_ADVANCE_TIMEOUT_IMAGE = 5000; // Milliseconds
    private static final int AUTO_ADVANCE_TIMEOUT_PDF = 5000; // Milliseconds

    /**
     * Set to true to automatically advance to next media item. For images this is after a set time, for video and audio when they are played.
     */
    private boolean autoAdvance = true;
    private boolean waitingForMoreData = false; // Set to true if we get an auto advance event while on the last item

    // If this is set, we are in "preview audio" mode.
    private MediaInfo recordedAudio;

    private int audioLoaderId = -1;

    public StoryView(ConversationDetailActivity activity) {
        super(activity);
        final LinearLayoutManager llm = new LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false);
        llm.setStackFromEnd(false);
        mHistory.setLayoutManager(llm);
        snapHelper = new PagerSnapHelper();
        snapHelper.attachToRecyclerView(mHistory);

        progressBar = activity.findViewById(R.id.progress_horizontal);
        mHistory.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    if (currentPage != getCurrentPagePosition()) {
                        // Only react on change
                        setCurrentPage();
                        updateProgressCurrentPage();
                        autoAdvance = true;
                    }
                } else if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    getHistoryView().removeCallbacks(advanceToNextRunnable);
                    autoAdvance = false;
                }
            }

            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
            }
        });

          mMicButton = activity.findViewById(R.id.btnMic);
        mMicButton.setOnClickListener(null);
        mMicButton.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                captureAudioStart();
                return true;
            }
        });
        mMicButton.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (audioRecorder != null && audioRecorder.isAudioRecording() && (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL)) {
                    captureAudioStop(); // Stop!
                }
                return false;
            }
        });


        previewAudio = activity.findViewById(R.id.previewAudio);
        previewAudio.setVisibility(View.GONE);

        storyAudioPlayer = new StoryAudioPlayer(activity);
        storyAudioPlayerView = activity.findViewById(R.id.audioPlayerView);
        storyAudioPlayerView.setShowShuffleButton(false);
        storyAudioPlayerView.setShowMultiWindowTimeBar(true);
        storyAudioPlayerView.hide();
        storyAudioPlayerView.setPlayer(storyAudioPlayer.getPlayer());

        FloatingActionButton fabShowAudioPlayer = activity.findViewById(R.id.fabShowAudioPlayer);
        fabShowAudioPlayer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CoordinatorLayout.LayoutParams lp = (CoordinatorLayout.LayoutParams) fabShowAudioPlayer.getLayoutParams();
                if (storyAudioPlayerView.getVisibility() == View.VISIBLE) {
                    storyAudioPlayerView.hide();
                    lp.dodgeInsetEdges = Gravity.BOTTOM;
                    fabShowAudioPlayer.setImageResource(R.drawable.ic_audio_24dp);
                } else {
                    storyAudioPlayerView.show();
                    lp.dodgeInsetEdges = 0;
                    fabShowAudioPlayer.setImageResource(R.drawable.ic_close_white_24dp);
                    storyAudioPlayerView.getPlayer();

                }
                fabShowAudioPlayer.setLayoutParams(lp);
            }
        });

        mComposeMessage.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {

            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                autoAdvance = false;
            }

            @Override
            public void afterTextChanged(Editable s) {

            }
        });
        activity.findViewById(R.id.composeMessage).clearFocus();
   }


    @Override
    public boolean bindChat(long chatId, String address, String name) {
        // Destroy old audio loader
        if (audioLoaderId != -1) {
            mActivity.getSupportLoaderManager().destroyLoader(audioLoaderId);
            audioLoaderId = -1;
        }
        return super.bindChat(chatId, address, name);
    }

    @Override
    protected void onSendButtonClicked() {
        // If we have recorded audio, send that!
        if (recordedAudio != null) {
            // TODO Story - Send the audio! It's in recorderAudio.uri (not in VFS). Need to delete afterwards.
            ((StoryActivity)mActivity).sendMedia(recordedAudio.uri,"audio/m4a",true);
            setRecordedAudio(null);
            return;
        }
        super.onSendButtonClicked();
    }

    @Override
    protected void sendMessage() {
        super.sendMessage();
        View view = mActivity.getCurrentFocus();
        if (view != null) {
            InputMethodManager imm = (InputMethodManager) mActivity.getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }

    }

    private int getCurrentPagePosition() {
        View snapView = snapHelper.findSnapView(mHistory.getLayoutManager());
        if (snapView != null) {
            return mHistory.getLayoutManager().getPosition(snapView);
        }
        return RecyclerView.NO_POSITION;
    }

    private void updateProgressCurrentPage() {
        if (currentPage >= 0) {
            progressBar.setProgress(currentPage + 1);
        }
    }

    private void setCurrentPage() {

        if (currentPageViewHolder != null) {
            if (currentPageViewHolder.itemView instanceof SimpleExoPlayerView) {
                Player player = ((SimpleExoPlayerView)currentPageViewHolder.itemView).getPlayer();
                if (player != null) {
                    player.setPlayWhenReady(false);
                }
            }
        }

        currentPage = getCurrentPagePosition();
        currentPageViewHolder = (currentPage >= 0) ? getHistoryView().findViewHolderForAdapterPosition(currentPage) : null;
        if (currentPageViewHolder != null) {
            if (currentPageViewHolder.itemView instanceof SimpleExoPlayerView) {
                SimpleExoPlayerView playerView = (SimpleExoPlayerView) currentPageViewHolder.itemView;
                playerView.getPlayer().setPlayWhenReady(true);
            } else if (currentPageViewHolder.itemView instanceof PZSImageView) {
                getHistoryView().removeCallbacks(advanceToNextRunnable);
                getHistoryView().postDelayed(advanceToNextRunnable, AUTO_ADVANCE_TIMEOUT_IMAGE);
            } else if (currentPageViewHolder.itemView instanceof PDFView) {
                getHistoryView().removeCallbacks(advanceToNextRunnable);
                getHistoryView().postDelayed(advanceToNextRunnable, AUTO_ADVANCE_TIMEOUT_PDF);
            }
        }
    }

    @Override
    protected Loader<Cursor> createLoader() {
        String selection = "mime_type LIKE 'image/%' OR mime_type LIKE 'NOTaudio/%' OR mime_type LIKE 'video/%' OR mime_type LIKE 'application/pdf'";
        CursorLoader loader = new CursorLoader(mActivity, mUri, null, selection, null, Imps.Messages.REVERSE_SORT_ORDER);
        return loader;
    }

    @Override
    protected void loaderFinished() {
        // Dont call super, we don't want to scroll to last message
        //TODO - find last read message and scroll to that

        // If we are on the previously last message, advance?
        int n = getHistoryView().getAdapter().getItemCount();
        if (currentPage == n - 1 - 1 && autoAdvance && waitingForMoreData) {
            waitingForMoreData = false;
            getHistoryView().post(new Runnable() {
                @Override
                public void run() {
                    advanceToNext();
                }
            });
        }

        // Update audio cursor as well
        if (audioLoaderId == -1) {
            audioLoaderId = loaderId++;
        }
        mActivity.getSupportLoaderManager().restartLoader(audioLoaderId, null, new LoaderManager.LoaderCallbacks<Cursor>() {
            @NonNull
            @Override
            public Loader<Cursor> onCreateLoader(int i, @Nullable Bundle bundle) {
                String selection = "mime_type LIKE 'audio/%'";
                return new CursorLoader(mActivity, mUri, null, selection, null, Imps.Messages.DEFAULT_SORT_ORDER);
            }

            @Override
            public void onLoadFinished(@NonNull Loader<Cursor> loader, Cursor cursor) {
                int uriColumn = cursor.getColumnIndexOrThrow(Imps.Messages.BODY);
                int mimeTypeColumn = cursor.getColumnIndexOrThrow(Imps.Messages.MIME_TYPE);
                storyAudioPlayer.updateCursor(cursor, mimeTypeColumn, uriColumn);
            }

            @Override
            public void onLoaderReset(@NonNull Loader<Cursor> loader) {

            }
        });
    }

    @Override
    protected ConversationRecyclerViewAdapter createRecyclerViewAdapter() {
        return new StoryRecyclerViewAdapter(mActivity, null);
    }

    private void captureAudioStart() {
        mComposeMessage.setVisibility(View.INVISIBLE);

        // Start recording!
        if (audioRecorder == null) {
            audioRecorder = new AudioRecorder(previewAudio.getContext(), this);
        } else if (audioRecorder.isAudioRecording()) {
            audioRecorder.stopAudioRecording(true);
        }
        StoryExoPlayerManager.recordAudio(audioRecorder, previewAudio);
        audioRecorder.startAudioRecording();

        ((CircularPulseImageButton)mMicButton).setAnimating(true);
    }

    private void captureAudioStop() {
        ((CircularPulseImageButton)mMicButton).setAnimating(false);
        if (audioRecorder != null && audioRecorder.isAudioRecording()) {
            audioRecorder.stopAudioRecording(false);
        }
    }

    private void setRecordedAudio(MediaInfo recordedAudio) {
        this.recordedAudio = recordedAudio;
        if (this.recordedAudio != null) {
            mMicButton.setVisibility(View.GONE);
            mSendButton.setVisibility(View.VISIBLE);
            Drawable d = ActivityCompat.getDrawable(mActivity, R.drawable.ic_close_white_24dp).mutate();
            DrawableCompat.setTint(d, Color.GRAY);
            mActivity.getSupportActionBar().setHomeAsUpIndicator(d);
            mActivity.setBackButtonHandler(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    StoryExoPlayerManager.stop(previewAudio);
                    setRecordedAudio(null);
                }
            });
        } else {
            mActivity.getSupportActionBar().setHomeAsUpIndicator(null);
            mActivity.setBackButtonHandler(null);
            previewAudio.setVisibility(View.GONE);
            mComposeMessage.setVisibility(View.VISIBLE);
            mComposeMessage.setText("");
            mMicButton.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onAudioRecorded(Uri uri) {
        setRecordedAudio(new MediaInfo(uri, "audio/mp4"));
        StoryExoPlayerManager.load(recordedAudio, previewAudio, true);
    }

    class StoryRecyclerViewAdapter extends ConversationRecyclerViewAdapter implements PZSImageView.PSZImageViewImageMatrixListener {
        private final RequestOptions imageRequestOptions;

        public StoryRecyclerViewAdapter(Activity context, Cursor c) {
            super(context, c);
            imageRequestOptions = new RequestOptions().centerInside().diskCacheStrategy(DiskCacheStrategy.NONE).error(R.drawable.broken_image_large);
        }

        @Override
        public int getItemCount() {
            int count = super.getItemCount();
            if (progressBar != null) {
                progressBar.setMax(count);
                updateProgressCurrentPage();
            }
            return count;
        }

        @Override
        public int getItemViewType(int position) {
            try {
                Cursor c = getCursor();
                c.moveToPosition(position);
                String mime = c.getString(mMimeTypeColumn);
                if (!TextUtils.isEmpty(mime)) {
                    if (mime.startsWith("audio/") || mime.startsWith("video/")) {
                        return 1;
                    } else if (mime.contentEquals("application/pdf")) {
                        return 2;
                    }
                }
            } catch (Exception ignored) {
            }
            return 0; // Image
        }

        @Override
        public MessageViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {

            View mediaView = null;
            Context context = parent.getContext();

            switch (viewType) {
                case 2:
                    mediaView = LayoutInflater.from(context).inflate(R.layout.story_viewer_file_info, parent, false);
                    break;
                case 1:
                    SimpleExoPlayerView playerView = (SimpleExoPlayerView) LayoutInflater.from(context).inflate(R.layout.story_viewer_exo_player, parent, false);
                    mediaView = playerView;
                    mediaView.setBackgroundColor(0xff333333);
                    break;
                case 0:
                default:
                    PZSImageView imageView = new PZSImageView(context);
                    mediaView = imageView;
                    imageView.setBackgroundColor(0xff333333);
                    break;
            }

            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            mediaView.setLayoutParams(lp);

            MessageViewHolder mvh = new MessageViewHolder(mediaView);
            mvh.setLayoutInflater(LayoutInflater.from(parent.getContext()));
            mvh.setOnImageClickedListener(this);
            return mvh;
        }

        @Override
        public void onBindViewHolder(MessageViewHolder viewHolder, Cursor cursor) {

            int viewType = getItemViewType(cursor.getPosition());
            Context context = viewHolder.itemView.getContext();

            try {
                String mime = cursor.getString(mMimeTypeColumn);
                Uri uri = Uri.parse(cursor.getString(mBodyColumn));

                switch (viewType) {
                    case 2:
                        TextView infoView = (TextView)viewHolder.itemView.findViewById(R.id.text);
                        infoView.setText(uri.getLastPathSegment());
                        viewHolder.itemView.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                Intent intent = new Intent(context, PdfViewActivity.class);
                                intent.setDataAndType(uri,mime);
                                context.startActivity(intent);
                            }
                        });
                        break;
                    case 1:
                        SimpleExoPlayerView playerView = (SimpleExoPlayerView)viewHolder.itemView;
                        MediaInfo mediaInfo = new MediaInfo(uri, mime);
                        StoryExoPlayerManager.load(mediaInfo, playerView, false);
                        playerView.getPlayer().addListener(new Player.EventListener() {
                            @Override
                            public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
                                if (playbackState == Player.STATE_ENDED) {
                                    advanceToNext();
                                }
                            }
                        });
                        break;
                    case 0:
                    default:
                        PZSImageView imageView = (PZSImageView)viewHolder.itemView;

                        try {
                            imageView.setMatrixListener(this);
                            if (SecureMediaStore.isVfsUri(uri)) {

                                info.guardianproject.iocipher.File fileMedia = new info.guardianproject.iocipher.File(uri.getPath());

                                if (fileMedia.exists()) {
                                    Glide.with(context)
                                            .asBitmap()
                                            .apply(imageRequestOptions)
                                            .load(new info.guardianproject.iocipher.FileInputStream(fileMedia))
                                            .into(imageView);
                                } else {
                                    Glide.with(context)
                                            .asBitmap()
                                            .apply(imageRequestOptions)
                                            .load(R.drawable.broken_image_large)
                                            .into(imageView);
                                }
                            } else {
                                Glide.with(context)
                                        .asBitmap()
                                        .apply(imageRequestOptions)
                                        .load(uri)
                                        .into(imageView);
                            }
                        } catch (Throwable t) { // may run Out Of Memory
                            Log.w(LOG_TAG, "unable to load thumbnail: " + t);
                        }

                        break;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            if (currentPage == -1) {
                currentPage = 0;
                getHistoryView().post(new Runnable() {
                    @Override
                    public void run() {
                        setCurrentPage();
                    }
                });
            }
        }

        @Override
        public void onViewRecycled(@NonNull MessageViewHolder holder) {
            if (holder.itemView instanceof PDFView) {
                final PDFView pdfView = (PDFView)holder.itemView;
                pdfView.post(new Runnable() {
                    @Override
                    public void run() {
                        pdfView.recycle();
                    }
                });
            } else if (holder.itemView instanceof SimpleExoPlayerView) {
                ((SimpleExoPlayerView)holder.itemView).getPlayer().stop(true);
                ((SimpleExoPlayerView)holder.itemView).getPlayer().release();
                ((SimpleExoPlayerView)holder.itemView).setPlayer(null);
            }
            super.onViewRecycled(holder);
        }

        @Override
        public void onImageMatrixSet(PZSImageView view, int imageWidth, int imageHeight, Matrix imageMatrix) {
            //TODO
        }
    }

    private Runnable advanceToNextRunnable = new Runnable() {
        @Override
        public void run() {
            advanceToNext();
        }
    };

    private void advanceToNext() {
        if (autoAdvance) {
            if (currentPage >= 0) {
                // At end of data?
                if ((currentPage + 1) < getHistoryView().getAdapter().getItemCount()) {
                    waitingForMoreData = false;
                    getHistoryView().smoothScrollToPosition(currentPage + 1);
                } else {
                    waitingForMoreData = true;
                }
            }
        }
    }

    public void pause ()
    {
        storyAudioPlayer.getPlayer().stop();
    }
}
