/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2019 Looker Data Sciences, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.looker.rtl

import com.looker.sdk.AccessToken
import java.security.SecureRandom
import kotlin.experimental.and

// https://stackoverflow.com/a/52225984/74137
// TODO performance comparison of these two methods
fun ByteArray.toHexStr() = asUByteArray().joinToString("") { it.toString(16).padStart(2, '0') }

private val hexArray = "0123456789abcdef".toCharArray()

fun hexStr(bytes: UByteArray): String {
    val hexChars = CharArray(bytes.size * 2)
    for (j in bytes.indices) {
        val v = (bytes[j] and 0xFF.toUByte()).toInt()

        hexChars[j * 2] = hexArray[v ushr 4]
        hexChars[j * 2 + 1] = hexArray[v and 0x0F]
    }
    return String(hexChars)
}

@ExperimentalUnsignedTypes
class OauthSession : AuthSession {
    override val apiSettings: ApiSettings
    override val transport: Transport
    private val random = SecureRandom()
    // TODO does this need to be re-initialized in createAuthCodeRequestUrl()?
    private var codeVerifier = this.secureRandom(32)

    constructor(apiSettings: ApiSettings, transport: Transport = Transport(apiSettings)) : super(apiSettings,
            transport) {
        this.apiSettings = apiSettings
        this.transport = transport
    }

    fun requestToken(body: Values) : AuthToken {
        val response = this.transport.request<AccessToken>(
                HttpMethod.POST,
                "/api/token",
                mapOf(),
                body)
        val token = this.ok<AccessToken>(response)
        this.authToken = AuthToken(token)
        return this.authToken
    }

    override fun getToken() : AuthToken {
        if (!this.isAuthenticated()) {
            if (this.activeToken().refreshToken?.isNotEmpty()!!) {
                val config = this.apiSettings.readConfig()
                // fetch the token
                this.requestToken(mapOf(
                        "grant_type" to "request_token",
                        "refresh_token" to this.activeToken().refreshToken,
                        "client_id" to config["client_id"],
                        "redirect_uri" to config["redirect_uri"]
                ))
            }
        }
        return this.activeToken()
    }

    /**
     * Generate an OAuth2 authCode request URL
     */
    fun createAuthCodeRequestUrl(scope: String, state: String) : String {
        val codeChallenge = this.sha256hash(this.codeVerifier)
        val config = this.apiSettings.readConfig()
        val lookerUrl = config["looker_url"]
        return addQueryParams("$lookerUrl/auth", mapOf(
                "response_type" to "code",
                "client_id" to config["client_id"],
                "redirect_uri" to config["redirect_uri"],
                "scope" to scope,
                "state" to state,
                "code_challenge_method" to "S256",
                "code_challenge" to codeChallenge
        ))
    }

    fun redeemAuthCode(authCode: String, codeVerifier: String? = null) : AuthToken {
        val config = this.apiSettings.readConfig()
        return this.requestToken(mapOf(
                "grant_type" to "authorization_code",
                "code" to authCode,
                "code_verifier" to if (codeVerifier !== null) { hexStr(this.codeVerifier) } else "",
                "client_id" to config["client_id"],
                "redirect_uri" to config["redirect_uri"]
        ))
    }

    fun secureRandom(byteCount: Int) : UByteArray {
        val bytes = ByteArray(byteCount)
        this.random.nextBytes(bytes)
        return bytes.asUByteArray()
    }

    fun sha256hash(value: UByteArray) : String {
        return hexStr(value)
//        return value.toHexStr()
    }

    fun sha256hash(value: String) : String {
        val bytes = value.toByteArray().toUByteArray()
        return sha256hash(bytes)
    }
}
