package com.souko.soukoplayer.network

import android.annotation.SuppressLint
import com.souko.soukoplayer.models.Album
import com.souko.soukoplayer.models.Track
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import android.util.Log
import java.net.URLEncoder
import java.security.MessageDigest

class ServerException(message: String): Exception(message)
class AuthException(message: String): Exception(message)

// 搜索相关的数据类，不与系统冲突
data class NavArtist(
    val id: String,
    val name: String,
    val coverUrl: String? = null
)

data class NavSearchResult(
    val albums: List<Album>,
    val tracks: List<Track>,
    val artists: List<NavArtist> = emptyList()
)

object NavidromeApi {

    private val client = OkHttpClient()

    // 内存缓存：Key 是 trackId，Value 是歌词文本
    private val lyricsCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    @SuppressLint("DefaultLocale")

            /**
             * 联网抓取歌词（带内存缓存，避免重复请求造成卡顿）
             */
    fun getLyrics(
        trackId: String,
        artist: String = "",
        title: String = "",
        serverUrl: String = "",
        username: String = "",
        password: String = ""
    ): String {
        if (title.isEmpty()) return ""

        // 1. 优先检查内存缓存，如果缓存里有直接返回，实现“秒开”
        if (trackId.isNotEmpty() && lyricsCache.containsKey(trackId)) {
            val cachedLrc = lyricsCache[trackId]
            if (!cachedLrc.isNullOrBlank()) {
                Log.d("NavidromeAPI", "命中本地内存缓存，秒出歌词 -> $title")
                return cachedLrc
            }
        }

        // 2. 缓存没有再走网易云网络请求
        try {
            val encodedArtist = URLEncoder.encode(artist, "UTF-8")
            val encodedTitle = URLEncoder.encode(title, "UTF-8")

            val keyword = URLEncoder.encode("$artist $title", "UTF-8")
            val searchUrl = "https://music.163.com/api/search/get/web?s=$keyword&type=1&offset=0&total=true&limit=1"

            val searchRequest = Request.Builder()
                .url(searchUrl)
                .header("Referer", "https://music.163.com")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            var neteaseSongId: Long = 0
            client.newCall(searchRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body.string()
                    val json = JSONObject(body)
                    val result = json.optJSONObject("result")
                    val songs = result?.optJSONArray("songs")
                    if (songs != null && songs.length() > 0) {
                        neteaseSongId = songs.getJSONObject(0).optLong("id", 0)
                    }
                }
            }

            if (neteaseSongId == 0L) return ""

            val lyricUrl = "https://music.163.com/api/song/lyric?os=pc&id=$neteaseSongId&lv=-1&kv=-1&tv=-1"
            val lyricRequest = Request.Builder()
                .url(lyricUrl)
                .header("Referer", "https://music.163.com")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            client.newCall(lyricRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body.string()
                    val json = JSONObject(body)
                    val lrcObj = json.optJSONObject("lrc")
                    val lyricText = lrcObj?.optString("lyric", "") ?: ""

                    if (lyricText.isNotBlank()) {
                        Log.d("NavidromeAPI", "网易云音乐成功获取歌词并写入缓存 -> $title")
                        // 3. 写入缓存
                        if (trackId.isNotEmpty()) {
                            lyricsCache[trackId] = lyricText
                        }
                        return lyricText
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("NavidromeAPI", "网易云音乐歌词抓取异常", e)
        }

        return ""
    }

    /** 获取专辑列表 */
    fun getAlbums(serverUrl: String, username: String, password: String): List<Album> {
        val salt = System.currentTimeMillis().toString()
        val token = md5(password + salt)

        val url = "$serverUrl/rest/getAlbumList2" +
                "?u=$username" +
                "&t=$token" +
                "&s=$salt" +
                "&v=1.16.1" +
                "&c=SoukoPlayer" +
                "&f=json" +
                "&type=newest" +
                "&size=10000"

        val request = Request.Builder().url(url).build()

        client.newCall(request).execute().use { response ->
            val body = response.body.string()
            Log.d("NavidromeAPI", "getAlbums 响应码: ${response.code}")
            Log.d("NavidromeAPI", "getAlbums 响应体: $body")

            if (!response.isSuccessful) {
                if (response.code in 400..499) throw AuthException("账号或密码错误")
                else throw ServerException("服务器地址错误或无法访问")
            }

            return parseAlbumsResponse(body, serverUrl, username, salt, token)
        }
    }

    /** 获取专辑曲目 */
    fun getTracks(albumId: String, serverUrl: String, username: String, password: String): List<Track> {
        val salt = System.currentTimeMillis().toString()
        val token = md5(password + salt)
        val url = "$serverUrl/rest/getMusicDirectory" +
                "?u=$username&t=$token&s=$salt&v=1.16.1&c=SoukoPlayer&f=json&id=$albumId"

        val request = Request.Builder().url(url).build()

        client.newCall(request).execute().use { response ->
            val body = response.body.string()
            Log.d("NavidromeAPI", "getTracks 响应码: ${response.code}")
            Log.d("NavidromeAPI", "getTracks 响应体: $body")

            if (!response.isSuccessful) {
                if (response.code in 400..499) throw AuthException("账号或密码错误")
                else throw ServerException("服务器地址错误或无法访问")
            }

            return parseTracksResponse(body, serverUrl, username, salt, token)
        }
    }

    /** 解析专辑列表 */
    private fun parseAlbumsResponse(
        body: String,
        serverUrl: String,
        username: String,
        salt: String,
        token: String
    ): List<Album> {
        val jsonResponse = JSONObject(body)
        val subsonicResponse = jsonResponse.getJSONObject("subsonic-response")

        if (subsonicResponse.getString("status") != "ok") {
            val errorCode = subsonicResponse.optJSONObject("error")?.optInt("code") ?: -1
            val errorMsg = subsonicResponse.optJSONObject("error")?.optString("message") ?: "未知错误"
            if (errorCode == 40) throw AuthException("账号或密码错误")
            else throw ServerException("服务器错误: $errorMsg")
        }

        val albumList = subsonicResponse.optJSONObject("albumList2") ?: return emptyList()
        val albumsArray = albumList.optJSONArray("album") ?: return emptyList()
        val list = mutableListOf<Album>()

        for (i in 0 until albumsArray.length()) {
            val albumObj = albumsArray.getJSONObject(i)
            val createdTime = albumObj.optLong("created", 0L)
            val coverArtId = albumObj.optString("coverArt", "")
            val coverUrl = if (coverArtId.isNotEmpty()) {
                "$serverUrl/rest/getCoverArt" +
                        "?id=$coverArtId&size=300&u=$username&t=$token&s=$salt&v=1.16.1&c=SoukoPlayer"
            } else ""
            list.add(Album(
                id = albumObj.getString("id"),
                title = albumObj.getString("name"),
                artist = albumObj.getString("artist"),
                year = albumObj.optString("year", ""),
                coverUrl = coverUrl,
                created = createdTime
            ))
        }

        return list.sortedByDescending { it.created }
    }

    /** 解析曲目列表 */
    private fun parseTracksResponse(
        body: String,
        serverUrl: String,
        username: String,
        salt: String,
        token: String
    ): List<Track> {
        val jsonResponse = JSONObject(body)
        val subsonicResponse = jsonResponse.getJSONObject("subsonic-response")

        if (subsonicResponse.getString("status") != "ok") {
            val errorCode = subsonicResponse.optJSONObject("error")?.optInt("code") ?: -1
            val errorMsg = subsonicResponse.optJSONObject("error")?.optString("message") ?: "未知错误"
            if (errorCode == 40) throw AuthException("账号或密码错误")
            else throw ServerException("服务器错误: $errorMsg")
        }

        val directory = subsonicResponse.optJSONObject("directory") ?: return emptyList()
        val childrenArray = directory.optJSONArray("child") ?: return emptyList()
        val list = mutableListOf<Track>()

        for (i in 0 until childrenArray.length()) {
            val child = childrenArray.getJSONObject(i)
            if (child.optString("isDir", "false") == "false") {
                val durationSec = child.optInt("duration", 0)

                // 1. 尝试解析歌曲或专辑封面
                val coverArtId = child.optString("coverArt", child.optString("albumId", ""))
                val coverUrl = if (coverArtId.isNotEmpty()) {
                    "$serverUrl/rest/getCoverArt?id=$coverArtId&size=300&u=$username&t=$token&s=$salt&v=1.16.1&c=SoukoPlayer"
                } else ""

                // 2. 解析年份 (Int)
                val trackYear = child.optInt("year", 0)

                list.add(Track(
                    id = child.getString("id"),
                    title = child.getString("title"),
                    artist = child.optString("artist", "未知艺术家"),
                    albumId = child.optString("parent", ""),
                    album = child.optString("album", "未知专辑"),
                    year = trackYear,
                    coverUrl = coverUrl,
                    streamUrl = "$serverUrl/rest/stream?id=${child.getString("id")}&u=$username&t=$token&s=$salt&v=1.16.1&c=SoukoPlayer",
                    duration = durationSec
                ))
            }
        }

        Log.d("NavidromeAPI", "解析到曲目数量: ${list.size}")
        return list
    }

    /** MD5 加密 */
    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** 搜索方法 - 支持专辑、歌曲、艺术家 */
    fun search(query: String, serverUrl: String, username: String, password: String): NavSearchResult {
        val salt = System.currentTimeMillis().toString()
        val token = md5(password + salt)
        val url = "$serverUrl/rest/search2" +
                "?u=$username&t=$token&s=$salt&v=1.16.1&c=SoukoPlayer&f=json" +
                "&query=${URLEncoder.encode(query, "UTF-8")}"

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("搜索失败: ${response.code}")
            }

            val body = response.body.string()
            Log.d("NavidromeAPI", "搜索 JSON 响应: $body")
            return parseSearchResult(body, serverUrl, username, salt, token)
        }
    }

    /** 解析搜索结果 */
    private fun parseSearchResult(
        body: String,
        serverUrl: String,
        username: String,
        salt: String,
        token: String
    ): NavSearchResult {
        val jsonResponse = JSONObject(body)
        val subsonicResponse = jsonResponse.getJSONObject("subsonic-response")
        if (subsonicResponse.getString("status") != "ok") {
            val errorMsg = subsonicResponse.optJSONObject("error")?.optString("message") ?: "未知错误"
            throw ServerException("搜索失败: $errorMsg")
        }

        val searchResult = subsonicResponse.optJSONObject("searchResult2")
            ?: return NavSearchResult(emptyList(), emptyList(), emptyList())

        val albums = mutableListOf<Album>()
        val tracks = mutableListOf<Track>()
        val artists = mutableListOf<NavArtist>()

        // 专辑
        searchResult.optJSONArray("album")?.let { array ->
            for (i in 0 until array.length()) {
                val albumJson = array.getJSONObject(i)
                val coverArtId = albumJson.optString("coverArt", "")
                val coverUrl = if (coverArtId.isNotEmpty()) {
                    "$serverUrl/rest/getCoverArt?id=$coverArtId&size=300&u=$username&t=$token&s=$salt&v=1.16.1&c=SoukoPlayer"
                } else ""
                albums.add(Album(
                    id = albumJson.getString("id"),
                    title = albumJson.getString("name"),
                    artist = albumJson.getString("artist"),
                    year = albumJson.optString("year", ""),
                    coverUrl = coverUrl,
                    created = albumJson.optLong("created", 0L)
                ))
            }
        }

        // 歌曲
        searchResult.optJSONArray("song")?.let { array ->
            for (i in 0 until array.length()) {
                val songJson = array.getJSONObject(i)

                val coverArtId = songJson.optString("coverArt", songJson.optString("albumId", ""))
                val coverUrl = if (coverArtId.isNotEmpty()) {
                    "$serverUrl/rest/getCoverArt?id=$coverArtId&size=300&u=$username&t=$token&s=$salt&v=1.16.1&c=SoukoPlayer"
                } else ""

                val trackYear = songJson.optInt("year", 0)

                tracks.add(Track(
                    id = songJson.getString("id"),
                    title = songJson.getString("title"),
                    artist = songJson.optString("artist", "未知艺术家"),
                    albumId = songJson.optString("albumId", songJson.optString("parent", "")),
                    album = songJson.optString("album", "未知专辑"),
                    year = trackYear,
                    coverUrl = coverUrl,
                    streamUrl = "$serverUrl/rest/stream?id=${songJson.getString("id")}&u=$username&t=$token&s=$salt&v=1.16.1&c=SoukoPlayer",
                    duration = songJson.optInt("duration", 0)
                ))
            }
        }

        // 艺术家
        searchResult.optJSONArray("artist")?.let { array ->
            for (i in 0 until array.length()) {
                val artistJson = array.getJSONObject(i)
                val coverArtId = artistJson.optString("coverArt", "")
                val coverUrl = if (coverArtId.isNotEmpty()) {
                    "$serverUrl/rest/getCoverArt?id=$coverArtId&size=300&u=$username&t=$token&s=$salt&v=1.16.1&c=SoukoPlayer"
                } else ""
                artists.add(NavArtist(
                    id = artistJson.getString("id"),
                    name = artistJson.getString("name"),
                    coverUrl = coverUrl
                ))
            }
        }

        return NavSearchResult(albums, tracks, artists)
    }
}