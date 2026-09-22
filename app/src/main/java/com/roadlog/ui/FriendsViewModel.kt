package com.roadlog.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roadlog.data.Friend
import com.roadlog.data.FriendLocation
import com.roadlog.data.FriendRequest
import com.roadlog.data.FriendsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FriendWithLocation(val friend: Friend, val location: FriendLocation?)

@OptIn(ExperimentalCoroutinesApi::class)
class FriendsViewModel(private val friendsRepository: FriendsRepository) : ViewModel() {

    val incomingRequests: StateFlow<List<FriendRequest>> = friendsRepository.observeIncomingRequests()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val friendsWithLocation: StateFlow<List<FriendWithLocation>> = friendsRepository.observeFriends()
        .flatMapLatest { friends ->
            if (friends.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(
                    friends.map { friend ->
                        friendsRepository.observeFriendLocation(friend.uid)
                            .map { location -> FriendWithLocation(friend, location) }
                    }
                ) { it.toList() }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun sendFriendRequest(email: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            onResult(friendsRepository.sendFriendRequest(email))
        }
    }

    fun acceptRequest(request: FriendRequest) {
        viewModelScope.launch { friendsRepository.acceptFriendRequest(request) }
    }

    fun declineRequest(request: FriendRequest) {
        viewModelScope.launch { friendsRepository.declineFriendRequest(request) }
    }
}
