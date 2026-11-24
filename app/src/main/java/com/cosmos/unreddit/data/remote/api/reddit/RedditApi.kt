package com.cosmos.unreddit.data.remote.api.reddit

import com.cosmos.unreddit.data.model.Sort
import com.cosmos.unreddit.data.model.TimeSorting
import com.cosmos.unreddit.data.remote.api.reddit.model.Child
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

interface RedditApi {

    @GET
    suspend fun getSubredditPage(@Url url: String): ResponseBody

    @GET("/by_id/{names}")
    suspend fun getPostById(@Path("names") names: String): ResponseBody

    @Headers("Accept: text/vnd.reddit.partial+html, text/html;q=0.9")
    @GET
    suspend fun getShredditComments(
        @Url url: String,
        @Query("sort") sort: Sort,
        @Query("render-mode") renderMode: String = "partial"
    ): ResponseBody

    @Headers("Accept: text/vnd.reddit.partial+html, text/html;q=0.9")
    @POST
    suspend fun getShredditMoreComments(
        @Url url: String,
        @Body body: RequestBody
    ): ResponseBody

    //region Subreddit

    @GET("/{sort}")
    suspend fun getFrontpage(
        @Path("sort") sort: Sort,
        @Query("t") timeSorting: TimeSorting?,
        @Query("after") after: String? = null,
        @Query("geo_filter") geoFilter: String? = "GLOBAL"
    ): ResponseBody

    @GET("/r/{subreddit}/{sort}")
    suspend fun getSubreddit(
        @Path("subreddit", encoded = true) subreddit: String,
        @Path("sort") sort: Sort,
        @Query("t") timeSorting: TimeSorting?,
        @Query("after") after: String? = null,
        @Query("geo_filter") geoFilter: String? = "GLOBAL"
    ): ResponseBody

    @GET("/r/{subreddit}/about")
    suspend fun getSubredditInfo(@Path("subreddit") subreddit: String): ResponseBody

    @GET("/r/{subreddit}/search.json-html?restrict_sr=1&include_over_18=1")
    suspend fun searchInSubreddit(
        @Path("subreddit", encoded = true) subreddit: String,
        @Query("q") query: String,
        @Query("sort") sort: Sort?,
        @Query("t") timeSorting: TimeSorting?,
        @Query("after") after: String? = null
    ): ResponseBody

    @GET("/r/{subreddit}/about/rules")
    fun getSubredditRules(@Path("subreddit") subreddit: String): Call<Child>

    //endregion

    @GET("{permalink}")
    suspend fun getPost(
        @Path("permalink", encoded = true) permalink: String,
        @Query("limit") limit: Int? = null,
        @Query("sort") sort: Sort
    ): ResponseBody

    @GET("/api/morechildren?api_type=json")
    suspend fun getMoreChildren(
        @Query("children") children: String,
        @Query("link_id") linkId: String
    ): ResponseBody

    //region User

    @GET("/user/{user}/about")
    suspend fun getUserInfo(@Path("user") user: String): ResponseBody

    @GET("https://www.reddit.com/user/{user}/about.json?raw_json=1")
    suspend fun getUserInfoPublic(@Path("user") user: String): ResponseBody

    @GET("https://old.reddit.com/user/{user}/about.json?raw_json=1")
    suspend fun getUserInfoOldJson(@Path("user") user: String): ResponseBody

    @GET("/user/{user}/submitted/")
    suspend fun getUserPosts(
        @Path("user") user: String,
        @Query("sort") sort: Sort,
        @Query("t") timeSorting: TimeSorting?,
        @Query("after") after: String? = null
    ): ResponseBody

    @GET("/user/{user}/comments/")
    suspend fun getUserComments(
        @Path("user") user: String,
        @Query("sort") sort: Sort,
        @Query("t") timeSorting: TimeSorting?,
        @Query("after") after: String? = null
    ): ResponseBody

    //endregion

    //region Search

    @GET("/search.json-html?type=link&include_over_18=1")
    suspend fun searchPost(
        @Query("q") query: String,
        @Query("sort") sort: Sort?,
        @Query("t") timeSorting: TimeSorting?,
        @Query("after") after: String? = null
    ): ResponseBody

    @GET("/search.json-html?type=user&include_over_18=1")
    suspend fun searchUser(
        @Query("q") query: String,
        @Query("sort") sort: Sort?,
        @Query("t") timeSorting: TimeSorting?,
        @Query("after") after: String? = null
    ): ResponseBody

    @GET("/search.json-html?type=sr&include_over_18=1")
    suspend fun searchSubreddit(
        @Query("q") query: String,
        @Query("sort") sort: Sort?,
        @Query("t") timeSorting: TimeSorting?,
        @Query("after") after: String? = null
    ): ResponseBody

    @Headers("Accept: text/vnd.reddit.partial+html, text/html;q=0.9")
    @GET("https://www.reddit.com/svc/shreddit/search/")
    suspend fun searchShreddit(
        @Query("q") query: String,
        @Query("type") type: String,
        @Query("sort") sort: Sort? = null,
        @Query("t") timeSorting: TimeSorting? = null,
        @Query("cursor") cursor: String? = null,
        @Query("include_over_18") includeOver18: String = "1"
    ): ResponseBody

    @Headers("Accept: text/vnd.reddit.partial+html, text/html;q=0.9")
    @GET("https://www.reddit.com/svc/shreddit/r/{subreddit}/search/")
    suspend fun searchInSubredditShreddit(
        @Path("subreddit", encoded = true) subreddit: String,
        @Query("q") query: String,
        @Query("sort") sort: Sort? = null,
        @Query("t") timeSorting: TimeSorting? = null,
        @Query("cursor") cursor: String? = null,
        @Query("restrict_sr") restrictSr: String = "1",
        @Query("include_over_18") includeOver18: String = "1"
    ): ResponseBody

    @POST("/over18")
    suspend fun consentOver18(@Body body: RequestBody, @Query("dest") dest: String): ResponseBody

    //endregion

    companion object {
        const val BASE_URL = "https://www.reddit.com/"
        const val BASE_URL_OAUTH = "https://oauth.reddit.com/"
        const val BASE_URL_OLD = "https://old.reddit.com/"
    }
}
