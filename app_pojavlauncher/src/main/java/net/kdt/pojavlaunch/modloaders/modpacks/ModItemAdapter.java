package net.kdt.pojavlaunch.modloaders.modpacks;

import android.annotation.SuppressLint;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;
import androidx.recyclerview.widget.RecyclerView;

import com.kdt.SimpleArrayAdapter;

import net.kdt.pojavlaunch.PojavApplication;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.modloaders.modpacks.api.ModpackApi;
import net.kdt.pojavlaunch.modloaders.modpacks.imagecache.ImageReceiver;
import net.kdt.pojavlaunch.modloaders.modpacks.imagecache.ModIconCache;
import net.kdt.pojavlaunch.modloaders.modpacks.models.Constants;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModDetail;
import net.kdt.pojavlaunch.modloaders.modpacks.models.ModItem;
import net.kdt.pojavlaunch.modloaders.modpacks.models.SearchFilters;
import net.kdt.pojavlaunch.modloaders.modpacks.models.SearchResult;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.Future;

public class ModItemAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final ModItem[] MOD_ITEMS_EMPTY = new ModItem[0];
    private static final int VIEW_TYPE_MOD_ITEM = 0;
    private static final int VIEW_TYPE_LOADING = 1;

    /* Used when versions haven't loaded yet, default text to reduce layout shifting */
    private final SimpleArrayAdapter<String> mLoadingAdapter = new SimpleArrayAdapter<>(Collections.singletonList("Loading"));
    private final ModIconCache mIconCache = new ModIconCache();
    private final SearchResultCallback mSearchResultCallback;
    private ModItem[] mModItems;
    private final ModpackApi mModpackApi;

    /* Cache for ever so slightly rounding the image for the corner not to stick out of the layout */
    private final float mCornerDimensionCache;

    private Future<?> mTaskInProgress;
    private SearchFilters mSearchFilters;
    private SearchResult mCurrentResult;


    public ModItemAdapter(Resources resources, ModpackApi api, SearchResultCallback callback) {
        mCornerDimensionCache = resources.getDimension(R.dimen._1sdp) / 250;
        mModpackApi = api;
        mModItems = new ModItem[]{};
        mSearchResultCallback = callback;
    }

    public void performSearchQuery(SearchFilters searchFilters) {
        if(mTaskInProgress != null) {
            mTaskInProgress.cancel(true);
            mTaskInProgress = null;
        }
        this.mSearchFilters = searchFilters;
        mTaskInProgress = new SelfReferencingFuture(new SearchApiTask(mSearchFilters, null))
                .startOnExecutor(PojavApplication.sExecutorService);
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup viewGroup, int viewType) {
        LayoutInflater layoutInflater = LayoutInflater.from(viewGroup.getContext());
        View view;
        switch (viewType) {
            case VIEW_TYPE_MOD_ITEM:
                // Create a new view, which defines the UI of the list item
                view = layoutInflater.inflate(R.layout.view_mod, viewGroup, false);
                return new ViewHolder(view);
            case VIEW_TYPE_LOADING:
                // Create a new view, which is actually just the progress bar
                view = layoutInflater.inflate(R.layout.view_loading, viewGroup, false);
                return new LoadingViewHolder(view);
            default:
                throw new RuntimeException("Unimplemented view type!");
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        switch (getItemViewType(position)) {
            case VIEW_TYPE_MOD_ITEM:
                ((ModItemAdapter.ViewHolder)holder).setStateLimited(mModItems[position]);
                break;
            case VIEW_TYPE_LOADING:
                loadMoreResults();
                break;
            default:
                throw new RuntimeException("Unimplemented view type!");
        }
    }

    @Override
    public int getItemCount() {
        if(mModItems.length == 0) return 0;
        return mModItems.length+1;
    }

    private void loadMoreResults() {
        if(mTaskInProgress != null) return;
        mTaskInProgress = new SelfReferencingFuture(new SearchApiTask(mSearchFilters, mCurrentResult))
                .startOnExecutor(PojavApplication.sExecutorService);
    }

    @Override
    public int getItemViewType(int position) {
        if(position < mModItems.length) return VIEW_TYPE_MOD_ITEM;
        return VIEW_TYPE_LOADING;
    }



    /**
     * Basic viewholder with expension capabilities
     */
    public class ViewHolder extends RecyclerView.ViewHolder {

        private ModDetail mModDetail = null;
        private ModItem mModItem = null;
        private final TextView mTitle, mDescription;
        private final ImageView mIconView, mSourceView;
        private View mExtendedLayout;
        private Spinner mExtendedSpinner;
        private Button mExtendedButton;
        private TextView mExtendedErrorTextView;
        private Future<?> mExtensionFuture;
        private Bitmap mThumbnailBitmap;
        private ImageReceiver mImageReceiver;

        /* Used to display available versions of the mod(pack) */
        private final SimpleArrayAdapter<String> mVersionAdapter = new SimpleArrayAdapter<>(null);

        public ViewHolder(View view) {
            super(view);

            view.setOnClickListener(v -> {
                if(!hasExtended()){
                    // Inflate the ViewStub
                    mExtendedLayout = ((ViewStub)v.findViewById(R.id.mod_limited_state_stub)).inflate();
                    mExtendedButton = mExtendedLayout.findViewById(R.id.mod_extended_select_version_button);
                    mExtendedSpinner = mExtendedLayout.findViewById(R.id.mod_extended_version_spinner);
                    mExtendedErrorTextView = mExtendedLayout.findViewById(R.id.mod_extended_error_textview);

                    mExtendedButton.setOnClickListener(v1 -> mModpackApi.handleInstallation(
                            mExtendedButton.getContext().getApplicationContext(),
                            mModDetail,
                            mExtendedSpinner.getSelectedItemPosition()));
                    mExtendedSpinner.setAdapter(mLoadingAdapter);
                } else {
                    if(isExtended()) closeDetailedView();
                    else openDetailedView();
                }

                if(isExtended() && mModDetail == null && mExtensionFuture == null) { // only reload if no reloads are in progress
                    setDetailedStateDefault();
                    /*
                     * Why do we do this?
                     * The reason is simple: multithreading is difficult as hell to manage
                     * Let me explain:
                     */
                    mExtensionFuture = new SelfReferencingFuture(myFuture -> {
                        /*
                         * While we are sitting in the function below doing networking, the view might have already gotten recycled.
                         * If we didn't use a Future, we would have extended a ViewHolder with completely unrelated content
                         * or with an error that has never actually happened
                         */
                        mModDetail = mModpackApi.getModDetails(mModItem);
                        System.out.println(mModDetail);
                        Tools.runOnUiThread(() -> {
                            /*
                             * Once we enter here, the state we're in is already defined - no view shuffling can happen on the UI
                             * thread while we are on the UI thread ourselves. If we were cancelled, this means that the future
                             * we were supposed to have no longer makes sense, so we return and do not alter the state (since we might
                             * alter the state of an unrelated item otherwise)
                             */
                            if(myFuture.isCancelled()) return;
                            /*
                             * We do not null the future before returning since this field might already belong to a different item with its
                             * own Future, which we don't want to interfere with.
                             * But if the future is not cancelled, it is the right one for this ViewHolder, and we don't need it anymore, so
                             * let's help GC clean it up once we exit!
                             */
                            mExtensionFuture = null;
                            setStateDetailed(mModDetail);
                        });
                    }).startOnExecutor(PojavApplication.sExecutorService);
                }
            });

            // Define click listener for the ViewHolder's View
            mTitle = view.findViewById(R.id.mod_title_textview);
            mDescription = view.findViewById(R.id.mod_body_textview);
            mIconView = view.findViewById(R.id.mod_thumbnail_imageview);
            mSourceView = view.findViewById(R.id.mod_source_imageview);
        }

        /** Display basic info about the moditem */
        public void setStateLimited(ModItem item) {
            mModDetail = null;
            if(mThumbnailBitmap != null) {
                mIconView.setImageBitmap(null);
                mThumbnailBitmap.recycle();
            }
            if(mImageReceiver != null) {
                mIconCache.cancelImage(mImageReceiver);
            }
            if(mExtensionFuture != null) {
                /*
                 * Since this method reinitializes the ViewHolder for a new mod, this Future stops being ours, so we cancel it
                 * and null it. The rest is handled above
                 */
                mExtensionFuture.cancel(true);
                mExtensionFuture = null;
            }

            mModItem = item;
            // here the previous reference to the image receiver will disappear
            mImageReceiver = bm->{
                mImageReceiver = null;
                mThumbnailBitmap = bm;
                RoundedBitmapDrawable drawable = RoundedBitmapDrawableFactory.create(mIconView.getResources(), bm);
                drawable.setCornerRadius(mCornerDimensionCache * bm.getHeight());
                mIconView.setImageDrawable(drawable);
            };
            mIconCache.getImage(mImageReceiver, mModItem.getIconCacheTag(), mModItem.imageUrl);
            mSourceView.setImageResource(getSourceDrawable(item.apiSource));
            mTitle.setText(item.title);
            mDescription.setText(item.description);

            if(hasExtended()){
                closeDetailedView();
            }
        }

        /** Display extended info/interaction about a modpack */
        private void setStateDetailed(ModDetail detailedItem) {

            if(detailedItem != null) {
                mExtendedButton.setEnabled(true);
                mExtendedErrorTextView.setVisibility(View.GONE);
                mVersionAdapter.setObjects(Arrays.asList(detailedItem.versionNames));
                mExtendedSpinner.setAdapter(mVersionAdapter);
            } else {
                closeDetailedView();
                mExtendedButton.setEnabled(false);
                mExtendedErrorTextView.setVisibility(View.VISIBLE);
                mExtendedSpinner.setAdapter(null);
                mVersionAdapter.setObjects(null);
            }
        }

        private void openDetailedView() {
            mExtendedLayout.setVisibility(View.VISIBLE);
            mDescription.setMaxLines(99);

            // We need to align to the longer section
            int futureBottom = mDescription.getBottom() + Tools.mesureTextviewHeight(mDescription) - mDescription.getHeight();
            ConstraintLayout.LayoutParams params = (ConstraintLayout.LayoutParams) mExtendedLayout.getLayoutParams();
            params.topToBottom = futureBottom > mIconView.getBottom() ? R.id.mod_body_textview : R.id.mod_thumbnail_imageview;
            mExtendedLayout.setLayoutParams(params);
        }

        private void closeDetailedView(){
            mExtendedLayout.setVisibility(View.GONE);
            mDescription.setMaxLines(3);
        }

        private void setDetailedStateDefault() {
            mExtendedButton.setEnabled(false);
            mExtendedSpinner.setAdapter(mLoadingAdapter);
            mExtendedErrorTextView.setVisibility(View.GONE);
            openDetailedView();
        }

        private boolean hasExtended(){
            return mExtendedLayout != null;
        }

        private boolean isExtended(){
            return hasExtended() && mExtendedLayout.getVisibility() == View.VISIBLE;
        }

        private int getSourceDrawable(int apiSource) {
            switch (apiSource) {
                case Constants.SOURCE_CURSEFORGE:
                    return R.drawable.ic_curseforge;
                case Constants.SOURCE_MODRINTH:
                    return R.drawable.ic_modrinth;
                default:
                    throw new RuntimeException("Unknown API source");
            }
        }
    }

    /**
     * The view holder used to hold the progress bar at the end of the list
     */
    private static class LoadingViewHolder extends RecyclerView.ViewHolder {
        public LoadingViewHolder(View view) {
            super(view);
        }
    }

    private class SearchApiTask implements SelfReferencingFuture.FutureInterface {
        private final SearchFilters mSearchFilters;
        private final SearchResult mPreviousResult;

        private SearchApiTask(SearchFilters searchFilters, SearchResult previousResult) {
            this.mSearchFilters = searchFilters;
            this.mPreviousResult = previousResult;
        }

        @SuppressLint("NotifyDataSetChanged")
        @Override
        public void run(Future<?> myFuture) {
            SearchResult result = mModpackApi.searchMod(mSearchFilters, mPreviousResult);
            ModItem[] resultModItems = result != null ? result.results : null;
            Tools.runOnUiThread(() -> {
                if(myFuture.isCancelled()) return;

                if(resultModItems == null) {
                    mSearchResultCallback.onSearchError(SearchResultCallback.ERROR_INTERNAL);
                }else if(resultModItems.length == 0) {
                    mSearchResultCallback.onSearchError(SearchResultCallback.ERROR_NO_RESULTS);
                }else{
                    mSearchResultCallback.onSearchFinished();
                }
                mCurrentResult = result;
                if(resultModItems == null) {
                    mModItems = MOD_ITEMS_EMPTY;
                    mTaskInProgress = null;
                    notifyDataSetChanged();
                    return;
                }
                if(mPreviousResult != null) {
                    ModItem[] newModItems = new ModItem[resultModItems.length + mModItems.length];
                    System.arraycopy(mModItems, 0, newModItems, 0, mModItems.length);
                    System.arraycopy(resultModItems, 0, newModItems, mModItems.length, resultModItems.length);
                    mModItems = newModItems;
                    mTaskInProgress = null;
                    notifyItemChanged(mModItems.length);
                    notifyItemRangeInserted(mModItems.length+1, newModItems.length);
                    return;
                }
                mModItems = resultModItems;
                mTaskInProgress = null;
                notifyDataSetChanged();
            });
        }
    }

    public interface SearchResultCallback {
        int ERROR_INTERNAL = 0;
        int ERROR_NO_RESULTS = 1;
        void onSearchFinished();
        void onSearchError(int error);
    }
}