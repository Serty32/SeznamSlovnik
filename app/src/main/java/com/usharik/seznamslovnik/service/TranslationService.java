package com.usharik.seznamslovnik.service;

import android.util.Log;
import android.util.Pair;

import com.usharik.seznamslovnik.AppState;
import com.usharik.seznamslovnik.dao.AppDatabase;
import com.usharik.seznamslovnik.dao.TranslationStorageDao;
import com.usharik.seznamslovnik.dao.Word;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.reactivex.Maybe;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.subjects.PublishSubject;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;

import static java.net.HttpURLConnection.HTTP_OK;

/**
 * Created by macbook on 09.03.2018.
 */

public class TranslationService {

    private static final List<String> EMPTY_STR_LIST = Collections.unmodifiableList(new ArrayList<>());

    private final PublishSubject<Wrapper> storeSubject = PublishSubject.create();
    private final TranslationStorageDao dao;
    private final AppState appState;
    private final Retrofit retrofit;
    private final PublishSubject<String> toastShowSubject;

    public TranslationService(final AppDatabase appDatabase,
                              final AppState appState,
                              final Retrofit retrofit,
                              final PublishSubject<String> toastShowSubject) {
        this.dao = appDatabase.translationStorageDao();
        this.appState = appState;
        this.retrofit = retrofit;
        this.toastShowSubject = toastShowSubject;
        storeSubject.observeOn(Schedulers.io())
                .subscribe((wrp) -> {
                    try {
                        dao.insertTranslationsForWord(wrp.word, wrp.langFrom, wrp.translations, wrp.langTo);
                    } catch (Exception ex) {
                        Log.e("!!!!!!!!!!!!!!!!!", ex.getLocalizedMessage());
                    }
                });
    }

    public Observable<List<String>> getSuggestions(String template, String langFrom, int limit) {
        return Observable.create((emitter) -> {
            List<String> suggestions = dao.getSuggestions(template, langFrom, limit);
            emitter.onNext(suggestions);
            emitter.onComplete();
        });
    }

    public Single<Pair<String, List<String>>> translate(String question, String langFrom, String langTo) {
        if (question == null || question.length() == 0) {
            return Single.just(Pair.create("", EMPTY_STR_LIST));
        }

        return existsActualTranslation(question, langFrom, langTo)
                .subscribeOn(Schedulers.io())
                .flatMap((exists) -> {
                    if (exists) {
                        return dao.getTranslations(question, langFrom, langTo, 1000)
                                .flatMap((list) -> Maybe.just(Pair.create(question, list)))
                                .toSingle();
                    } else if (!appState.isOfflineMode) {
                        return runOnlineTranslation(question, langFrom, langTo);
                    } else {
                        return Single.just(Pair.create(question, EMPTY_STR_LIST));
                    }
                });
    }

    private Single<Boolean> existsActualTranslation(String question, String langFrom, String langTo) {
        return dao.getWord(question, langFrom)
                .switchIfEmpty(Single.just(Word.NULL_WORD))
                .flatMap((word) -> {
                    if (word == Word.NULL_WORD || !checkTranslationAge(word)) {
                        return Single.just(EMPTY_STR_LIST);
                    } else {
                        return dao.getTranslations(question, langFrom, langTo, 1)
                                .switchIfEmpty(Single.just(EMPTY_STR_LIST));
                    }
                })
                .flatMap((list) -> Single.just(!list.isEmpty()));
    }

    private boolean checkTranslationAge(Word word) {
        Date curr = Calendar.getInstance().getTime();
        return TimeUnit.DAYS.convert(curr.getTime() - word.getLoadDate().getTime(), TimeUnit.MILLISECONDS) < 7;
    }

    private void storeTranslation(String word, String langFrom, List<String> translations, String langTo) {
        storeSubject.onNext(new Wrapper(word, langFrom, translations, langTo));
    }

    private Single<Pair<String, List<String>>> runOnlineTranslation(String question, String langFrom, String langTo) {
        PublishSubject<Pair<String, List<String>>> translationPublisher = PublishSubject.create();
        APIInterface apiInterface = retrofit.create(APIInterface.class);

        Call<ResponseBody> call = apiInterface.doTranslate(
                langFrom,
                langTo,
                question);

        call.enqueue(new Callback<ResponseBody>() {
            @Override
            public void onResponse(Call<ResponseBody> call, Response<ResponseBody> response) {
                if (response.code() != HTTP_OK) {
                    Log.e(getClass().getName(), "Http error " + response.code());
                    toastShowSubject.onNext("Http error " + response.code());
                    return;
                }
                try {
                    Document html = Jsoup.parse(response.body().string());

                    String word = question;
                    Elements elements1 = html.body().select("div.hgroup > h1");
                    if (elements1.size() > 0) {
                        word = elements1.get(0).text();
                    }

                    Elements elements = html.body().select("div#fastMeanings");
                    List<String> transList = Collections.EMPTY_LIST;
                    if (elements.size() > 0) {
                        transList = extractTranslations(elements.get(0).children());
                        if (transList.size() > 0) {
                            storeTranslation(word, langFrom, transList, langTo);
                        }
                    }
                    translationPublisher.onNext(Pair.create(word, transList));
                    translationPublisher.onComplete();
                } catch (Exception e) {
                    Log.e(getClass().getName(), e.getLocalizedMessage());
                    toastShowSubject.onNext(e.getLocalizedMessage());
                    translationPublisher.onNext(Pair.create(question, Collections.EMPTY_LIST));
                    translationPublisher.onComplete();
                }
            }

            @Override
            public void onFailure(Call<ResponseBody> call, Throwable t) {
                Log.e(getClass().getName(), t.getLocalizedMessage());
                toastShowSubject.onNext(t.getLocalizedMessage());
            }
        });
        return translationPublisher.singleOrError();
    }

    private static List<String> extractTranslations(Elements translations) {
        List<String> result = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        for (Element el : translations) {
            if (el.tag().getName().equals("br") || (el.tag().getName().equals("span") && el.hasClass("comma"))) {
                if (word.length() > 0) {
                    if (word.length() > 0) {
                        word.delete(word.length()-1, word.length());
                    }
                    result.add(word.toString());
                }
                word = new StringBuilder();
                continue;
            }
            word.append(el.text());
            word.append(" ");
        }
        return result;
    }

    private static class Wrapper {
        final String word;
        final String langFrom;
        final List<String> translations;
        final String langTo;

        Wrapper(String word, String langFrom, List<String> translations, String langTo) {
            this.word = word;
            this.langFrom = langFrom;
            this.langTo = langTo;
            this.translations = translations;
        }
    }
}