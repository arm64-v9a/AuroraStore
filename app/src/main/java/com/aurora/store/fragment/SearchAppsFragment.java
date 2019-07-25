/*
 * Aurora Store
 * Copyright (C) 2019, Rahul Kumar Patel <whyorean@gmail.com>
 *
 * Aurora Store is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * Aurora Store is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Aurora Store.  If not, see <http://www.gnu.org/licenses/>.
 *
 *
 */

package com.aurora.store.fragment;

import android.app.SearchManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SearchView;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.aurora.store.EndlessScrollListener;
import com.aurora.store.ErrorType;
import com.aurora.store.Filter;
import com.aurora.store.R;
import com.aurora.store.activity.AuroraActivity;
import com.aurora.store.activity.DetailsActivity;
import com.aurora.store.adapter.EndlessAppsAdapter;
import com.aurora.store.model.App;
import com.aurora.store.sheet.FilterBottomSheet;
import com.aurora.store.task.SearchTask;
import com.aurora.store.utility.Log;
import com.aurora.store.utility.NetworkUtil;
import com.aurora.store.utility.Util;
import com.aurora.store.utility.ViewUtil;
import com.bumptech.glide.Glide;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import butterknife.BindView;
import butterknife.ButterKnife;
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;

public class SearchAppsFragment extends BaseFragment {

    @BindView(R.id.search_apps_list)
    RecyclerView recyclerView;
    @BindView(R.id.search_apps)
    SearchView searchView;
    @BindView(R.id.filter_fab)
    ExtendedFloatingActionButton filterFab;
    @BindView(R.id.related_chip_group)
    ChipGroup relatedChipGroup;

    private Context context;
    private View view;
    private String query;
    private List<String> relatedTags = new ArrayList<>();
    private EndlessAppsAdapter endlessAppsAdapter;
    private SearchTask searchTask;

    private String getQuery() {
        return query;
    }

    private void setQuery(String query) {
        if (looksLikeAPackageId(query)) {
            context.startActivity(DetailsActivity.getDetailsIntent(getContext(), query));
        } else {
            this.query = query;
            fetchSearchAppsList(false);
        }
    }

    private boolean looksLikeAPackageId(String query) {
        if (TextUtils.isEmpty(query)) {
            return false;
        }
        String pattern = "([\\p{L}_$][\\p{L}\\p{N}_$]*\\.)+[\\p{L}_$][\\p{L}\\p{N}_$]*";
        Pattern r = Pattern.compile(pattern);
        return r.matcher(query).matches();
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        this.context = context;
        searchTask = new SearchTask(context);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        view = inflater.inflate(R.layout.fragment_search_applist, container, false);
        ButterKnife.bind(this, view);
        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setErrorView(ErrorType.NO_SEARCH);
        switchViews(true);
        setupSearch();
        setupRecycler();
        filterFab.setOnClickListener(v -> getFilterDialog());
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    public void setUserVisibleHint(boolean isVisibleToUser) {
        super.setUserVisibleHint(isVisibleToUser);
        if (isVisibleToUser) {
            if (searchView != null && Util.isIMEEnabled(context))
                searchView.requestFocus();
        }
    }

    @Override
    public void onDestroy() {
        Glide.with(this).pauseAllRequests();
        disposable.dispose();
        if (Util.filterSearchNonPersistent(context))
            new Filter(context).resetFilterPreferences();
        super.onDestroy();
    }

    private void setupSearch() {
        SearchManager searchManager = (SearchManager) context.getSystemService(Context.SEARCH_SERVICE);
        ComponentName componentName = getActivity().getComponentName();

        if (null != searchManager && componentName != null) {
            searchView.setSearchableInfo(searchManager.getSearchableInfo(componentName));
        }

        if (!StringUtils.isEmpty(AuroraActivity.externalQuery))
            setQuery(AuroraActivity.externalQuery);

        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextChange(String query) {
                if (StringUtils.isEmpty(query)) {
                    endlessAppsAdapter.clearData();
                    switchViews(true);
                    filterFab.hide();
                }
                return true;
            }

            @Override
            public boolean onQueryTextSubmit(String query) {
                searchView.clearFocus();
                setQuery(query);
                return true;
            }
        });

        searchView.setOnSuggestionListener(new SearchView.OnSuggestionListener() {
            @Override
            public boolean onSuggestionSelect(int position) {
                return true;
            }

            @Override
            public boolean onSuggestionClick(int position) {
                Cursor cursor = searchView.getSuggestionsAdapter().getCursor();
                cursor.moveToPosition(position);
                if (position == 0) {
                    searchView.setQuery(cursor.getString(2), true);
                    searchView.setQuery(cursor.getString(1), false);
                } else
                    searchView.setQuery(cursor.getString(1), true);
                setQuery(cursor.getString(0));
                return true;
            }
        });
    }

    private void getFilterDialog() {
        FilterBottomSheet filterSheet = new FilterBottomSheet();
        filterSheet.setCancelable(true);
        filterSheet.setOnApplyListener(v -> {
            filterSheet.dismiss();
            recyclerView.removeAllViewsInLayout();
            fetchSearchAppsList(false);
        });
        filterSheet.show(getChildFragmentManager(), "FILTER");
    }

    private void getIterator() {
        try {
            iterator = null;
            iterator = getIterator(getQuery());
            iterator.setEnableFilter(true);
            iterator.setFilter(new Filter(getContext()).getFilterPreferences());
            relatedTags = iterator.getRelatedTags();
        } catch (Exception e) {
            processException(e);
        }
    }

    private void fetchSearchAppsList(boolean shouldIterate) {
        if (!shouldIterate)
            getIterator();
        disposable.add(Observable.fromCallable(() -> searchTask.getSearchResults(iterator))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(appList -> {
                    if (view != null) {
                        if (shouldIterate) {
                            addApps(appList);
                        } else if (appList.isEmpty() && endlessAppsAdapter.isDataEmpty()) {
                            filterFab.hide(true);
                            setErrorView(ErrorType.NO_SEARCH_RESULT);
                            switchViews(true);
                        } else {
                            switchViews(false);
                            filterFab.show(true);
                            if (endlessAppsAdapter != null)
                                endlessAppsAdapter.addData(appList);
                            if (!relatedTags.isEmpty())
                                setupTags();
                        }
                    }
                }, err -> {
                    Log.e(err.getMessage());
                    processException(err);
                }));
    }

    private void addApps(List<App> appsToAdd) {
        if (!appsToAdd.isEmpty()) {
            for (App app : appsToAdd)
                endlessAppsAdapter.add(app);
            endlessAppsAdapter.notifyItemInserted(endlessAppsAdapter.getItemCount() - 1);
        }

        /*
         * Search results are scarce if filter are too strict, in this case endless scroll events
         * fail to fetch next batch of apps, so manually fetch at least 10 apps until available.
         */
        disposable.add(Observable.interval(1000, 2000, TimeUnit.MILLISECONDS)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(aLong -> {
                    if (iterator.hasNext() && endlessAppsAdapter.getItemCount() < 10) {
                        iterator.next();
                    }
                }, e -> Log.e(e.getMessage())));
    }

    private void setupRecycler() {
        LinearLayoutManager layoutManager = new LinearLayoutManager(getContext(), RecyclerView.VERTICAL, false);
        endlessAppsAdapter = new EndlessAppsAdapter(context);
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.setAdapter(endlessAppsAdapter);
        recyclerView.addOnScrollListener(new EndlessScrollListener(layoutManager) {
            @Override
            public void onLoadMore(int page, int totalItemsCount, RecyclerView view) {
                fetchSearchAppsList(true);
            }
        });
        recyclerView.setOnFlingListener(new RecyclerView.OnFlingListener() {
            @Override
            public boolean onFling(int velocityX, int velocityY) {
                if (velocityY < 0) {
                    filterFab.show();
                } else if (velocityY > 0) {
                    filterFab.hide();
                }
                return false;
            }
        });
    }

    private void setupTags() {
        relatedChipGroup.removeAllViews();
        int i = 0;
        for (String tag : relatedTags) {
            final int color = ViewUtil.getSolidColors(i++);
            Chip chip = new Chip(context);
            chip.setText(tag);
            chip.setChipStrokeWidth(3);
            chip.setChipStrokeColor(ColorStateList.valueOf(color));
            chip.setChipBackgroundColor(ColorStateList.valueOf(ColorUtils.setAlphaComponent(color, 100)));
            chip.setRippleColor(ColorStateList.valueOf(ColorUtils.setAlphaComponent(color, 200)));
            chip.setCheckedIcon(context.getDrawable(R.drawable.ic_chip_checked));
            chip.setOnClickListener(v -> {
                if (chip.isChecked()) {
                    query = query + " " + tag;
                    fetchData();
                } else {
                    query = query.replace(tag, "");
                    fetchData();
                }
            });
            relatedChipGroup.addView(chip);
        }
    }

    @Override
    protected void fetchData() {
        fetchSearchAppsList(false);
    }

    @Override
    protected View.OnClickListener errRetry() {
        return v -> {
            if (NetworkUtil.isConnected(context)) {
                fetchData();
            } else {
                setErrorView(ErrorType.NO_NETWORK);
            }
            ((Button) v).setText(getString(R.string.action_retry_ing));
            ((Button) v).setEnabled(false);
        };
    }
}