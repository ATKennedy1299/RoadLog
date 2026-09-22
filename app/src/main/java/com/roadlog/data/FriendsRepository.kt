package com.roadlog.data

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

data class Friend(val uid: String, val email: String, val displayName: String)
data class FriendRequest(val fromUid: String, val fromEmail: String, val fromDisplayName: String)
data class FriendLocation(
    val latitude: Double,
    val longitude: Double,
    val speedMps: Double,
    val timestampEpochMs: Long
)

/**
 * MVP data layer for accounts/friends/live location, talking directly to
 * Firebase Realtime Database — there is no server of our own. This is
 * explicitly an MVP: there are no Firebase Security Rules restricting any
 * of these paths yet, so anyone who knew another user's uid could read or
 * write it. Fine for two known devices; not fine to leave this way before
 * wider use — see the setup notes in RoadLogApp.
 *
 * Layout:
 *   /users/{uid}                      -> { email, displayName }
 *   /friendRequests/{toUid}/{fromUid} -> { fromEmail, fromDisplayName }
 *   /connections/{uid}/{otherUid}     -> true (written under both uids)
 *   /liveLocations/{uid}              -> { lat, lng, speedMps, timestampEpochMs }
 */
class FriendsRepository(private val authRepository: AuthRepository) {
    private val db = FirebaseDatabase.getInstance()

    private val myUid: String? get() = authRepository.uid

    suspend fun ensureProfileWritten(displayName: String) {
        val uid = myUid ?: return
        val email = authRepository.email ?: return
        db.getReference("users").child(uid).setValue(
            mapOf("email" to email, "displayName" to displayName)
        ).await()
    }

    /** Looks up a user by email and sends them a friend request. False if no such user exists. */
    suspend fun sendFriendRequest(targetEmail: String): Boolean {
        val myUid = myUid ?: return false
        val myEmail = authRepository.email ?: return false
        val matches = db.getReference("users")
            .orderByChild("email")
            .equalTo(targetEmail.trim())
            .get()
            .await()
        val targetUid = matches.children.firstOrNull()?.key ?: return false
        if (targetUid == myUid) return false

        val myProfile = db.getReference("users").child(myUid).get().await()
        val myName = myProfile.child("displayName").getValue(String::class.java) ?: myEmail

        db.getReference("friendRequests").child(targetUid).child(myUid).setValue(
            mapOf("fromEmail" to myEmail, "fromDisplayName" to myName)
        ).await()
        return true
    }

    fun observeIncomingRequests(): Flow<List<FriendRequest>> = callbackFlow {
        val uid = myUid
        if (uid == null) {
            trySend(emptyList())
            awaitClose {}
            return@callbackFlow
        }
        val ref = db.getReference("friendRequests").child(uid)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val requests = snapshot.children.mapNotNull { child ->
                    val fromUid = child.key ?: return@mapNotNull null
                    val fromEmail = child.child("fromEmail").getValue(String::class.java)
                        ?: return@mapNotNull null
                    val fromDisplayName = child.child("fromDisplayName").getValue(String::class.java)
                        ?: fromEmail
                    FriendRequest(fromUid, fromEmail, fromDisplayName)
                }
                trySend(requests)
            }
            override fun onCancelled(error: DatabaseError) { close(error.toException()) }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    suspend fun acceptFriendRequest(request: FriendRequest) {
        val uid = myUid ?: return
        db.getReference("connections").child(uid).child(request.fromUid).setValue(true).await()
        db.getReference("connections").child(request.fromUid).child(uid).setValue(true).await()
        db.getReference("friendRequests").child(uid).child(request.fromUid).removeValue().await()
    }

    suspend fun declineFriendRequest(request: FriendRequest) {
        val uid = myUid ?: return
        db.getReference("friendRequests").child(uid).child(request.fromUid).removeValue().await()
    }

    fun observeFriends(): Flow<List<Friend>> = callbackFlow {
        val uid = myUid
        if (uid == null) {
            trySend(emptyList())
            awaitClose {}
            return@callbackFlow
        }
        val ref = db.getReference("connections").child(uid)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val friendUids = snapshot.children.mapNotNull { it.key }
                if (friendUids.isEmpty()) {
                    trySend(emptyList())
                    return
                }
                // One-shot fetch per change rather than N extra live
                // listeners just for name/email, which rarely change.
                db.getReference("users").get().addOnSuccessListener { usersSnapshot ->
                    val friends = friendUids.mapNotNull { friendUid ->
                        val node = usersSnapshot.child(friendUid)
                        val email = node.child("email").getValue(String::class.java)
                            ?: return@mapNotNull null
                        val displayName = node.child("displayName").getValue(String::class.java)
                            ?: email
                        Friend(friendUid, email, displayName)
                    }
                    trySend(friends)
                }
            }
            override fun onCancelled(error: DatabaseError) { close(error.toException()) }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    fun observeFriendLocation(friendUid: String): Flow<FriendLocation?> = callbackFlow {
        val ref = db.getReference("liveLocations").child(friendUid)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val lat = snapshot.child("lat").getValue(Double::class.java)
                val lng = snapshot.child("lng").getValue(Double::class.java)
                val speed = snapshot.child("speedMps").getValue(Double::class.java) ?: 0.0
                val timestamp = snapshot.child("timestampEpochMs").getValue(Long::class.java) ?: 0L
                trySend(if (lat != null && lng != null) FriendLocation(lat, lng, speed, timestamp) else null)
            }
            override fun onCancelled(error: DatabaseError) { close(error.toException()) }
        }
        ref.addValueEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
    }

    /** Called by LocationTrackingService periodically while a trip is being recorded. */
    fun pushLiveLocation(latitude: Double, longitude: Double, speedMps: Double, timestampEpochMs: Long) {
        val uid = myUid ?: return
        db.getReference("liveLocations").child(uid).setValue(
            mapOf(
                "lat" to latitude,
                "lng" to longitude,
                "speedMps" to speedMps,
                "timestampEpochMs" to timestampEpochMs
            )
        )
    }
}
