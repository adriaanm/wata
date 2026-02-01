package com.wata.integration

import com.wata.client.MatrixApi
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Integration tests for Matrix API authentication.
 *
 * These tests run against a local Conduit Matrix server.
 * Start the server first: cd test/docker && ./setup.sh
 *
 * Tests:
 * - Login with valid credentials
 * - Login with invalid password (should fail)
 * - Login with non-existent user (should fail)
 * - Verify access token is stored
 * - Verify whoami endpoint works after login
 */
class AuthenticationTest {

    private lateinit var api: MatrixApi
    private val logger = TestLogger("AuthTest")

    @Before
    fun setup() {
        // Check if server is running
        requireMatrixServerRunning(TEST_HOMESERVER)

        // Create API instance for each test
        api = MatrixApi(TEST_HOMESERVER, logger)
    }

    // ========================================================================
    // Login Tests
    // ========================================================================

    @Test
    fun loginWithValidCredentials_returnsUserIdAndAccessToken() {
        val response = api.login(
            username = TestUser.ALICE.username,
            password = TestUser.ALICE.password
        )

        // Verify response structure
        assertNotNull("Response should not be null", response)
        assertEquals("User ID should match", TestUser.ALICE.userId, response.user_id)
        assertNotNull("Access token should not be null", response.access_token)
        assertTrue("Access token should not be empty", response.access_token.isNotEmpty())
        assertNotNull("Device ID should not be null", response.device_id)

        // Verify token is stored in API
        assertEquals("API should store access token", response.access_token, api.getAccessToken())

        logger.log("Login successful: ${response.user_id}, device: ${response.device_id}")
    }

    @Test
    fun loginWithInvalidPassword_throwsException() {
        val exception = try {
            api.login(
                username = TestUser.ALICE.username,
                password = "wrongpassword123"
            )
            null
        } catch (e: Exception) {
            e
        }

        assertNotNull("Should throw exception for wrong password", exception)
        assertTrue(
            "Exception should mention authentication failure",
            exception?.message?.contains("403", ignoreCase = true) == true ||
            exception?.message?.contains("Forbidden", ignoreCase = true) == true ||
            exception?.message?.contains("Invalid", ignoreCase = true) == true
        )
        assertNull("Access token should not be stored", api.getAccessToken())

        logger.log("Correctly rejected login with wrong password")
    }

    @Test
    fun loginWithNonExistentUser_throwsException() {
        val exception = try {
            api.login(
                username = "nonexistent_user_12345",
                password = "anypassword"
            )
            null
        } catch (e: Exception) {
            e
        }

        assertNotNull("Should throw exception for non-existent user", exception)
        assertTrue(
            "Exception should mention user not found",
            exception?.message?.contains("403", ignoreCase = true) == true ||
            exception?.message?.contains("Forbidden", ignoreCase = true) == true
        )
        assertNull("Access token should not be stored", api.getAccessToken())

        logger.log("Correctly rejected login for non-existent user")
    }

    @Test
    fun login_storesAccessTokenForSubsequentRequests() {
        // Login
        val response = api.login(
            username = TestUser.ALICE.username,
            password = TestUser.ALICE.password
        )

        // Verify token is accessible
        val storedToken = api.getAccessToken()
        assertEquals("Stored token should match login response", response.access_token, storedToken)

        // Verify token works for authenticated request (whoami)
        val whoami = api.whoami()
        assertEquals("Whoami should return same user ID", TestUser.ALICE.userId, whoami.user_id)

        logger.log("Access token correctly stored and used for authenticated requests")
    }

    // ========================================================================
    // Whoami Tests
    // ========================================================================

    @Test
    fun whoami_returnsCurrentUserInfoAfterLogin() {
        // Login first
        api.login(
            username = TestUser.BOB.username,
            password = TestUser.BOB.password
        )

        // Call whoami
        val whoami = api.whoami()

        assertNotNull("Whoami response should not be null", whoami)
        assertEquals("User ID should match", TestUser.BOB.userId, whoami.user_id)
        assertNotNull("Device ID should not be null", whoami.device_id)

        logger.log("Whoami returned: ${whoami.user_id}, device: ${whoami.device_id}")
    }

    @Test
    fun whoamiWithoutLogin_throwsException() {
        // Create new API instance without login
        val freshApi = MatrixApi(TEST_HOMESERVER, logger)

        val exception = try {
            freshApi.whoami()
            null
        } catch (e: Exception) {
            e
        }

        assertNotNull("Should throw exception without login", exception)
        assertTrue(
            "Exception should mention authentication",
            exception?.message?.contains("401", ignoreCase = true) == true ||
            exception?.message?.contains("Unauthorized", ignoreCase = true) == true ||
            exception?.message?.contains("token", ignoreCase = true) == true
        )

        logger.log("Correctly rejected whoami without authentication")
    }

    // ========================================================================
    // Logout Tests
    // ========================================================================

    @Test
    fun logout_clearsAccessToken() {
        // Login
        api.login(
            username = TestUser.ALICE.username,
            password = TestUser.ALICE.password
        )

        assertNotNull("Access token should be stored after login", api.getAccessToken())

        // Logout
        api.logout()

        // Verify token is cleared
        assertNull("Access token should be null after logout", api.getAccessToken())

        logger.log("Logout cleared access token")
    }

    @Test
    fun cannotCallAuthenticatedEndpoints_afterLogout() {
        // Login and logout
        api.login(
            username = TestUser.ALICE.username,
            password = TestUser.ALICE.password
        )
        api.logout()

        // Try to call whoami (should fail)
        val exception = try {
            api.whoami()
            null
        } catch (e: Exception) {
            e
        }

        assertNotNull("Should throw exception after logout", exception)

        logger.log("Correctly rejected authenticated request after logout")
    }

    // ========================================================================
    // Session Resumption Tests
    // ========================================================================

    @Test
    fun canResumeSession_bySettingAccessToken() {
        // Login and get token
        val response = api.login(
            username = TestUser.ALICE.username,
            password = TestUser.ALICE.password
        )
        val accessToken = response.access_token

        // Create new API instance and set token
        val freshApi = MatrixApi(TEST_HOMESERVER, logger)
        freshApi.setAccessToken(accessToken)

        // Verify whoami works with set token
        val whoami = freshApi.whoami()
        assertEquals("Should resume session with set token", TestUser.ALICE.userId, whoami.user_id)

        logger.log("Successfully resumed session with saved access token")
    }
}
