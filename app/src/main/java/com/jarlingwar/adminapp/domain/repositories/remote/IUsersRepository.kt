package com.jarlingwar.adminapp.domain.repositories.remote

import com.jarlingwar.adminapp.domain.models.RemovedUser
import com.jarlingwar.adminapp.domain.models.UserModel
import com.jarlingwar.adminapp.domain.models.UsersQueryParams
import kotlinx.coroutines.flow.Flow

typealias SaveUserResponse = Result<Boolean>
typealias DeleteUserResponse = Result<Boolean>
typealias UserResponse = Result<UserModel?>

interface IUsersRepository {
    fun updateParams(queryParams: UsersQueryParams)
    fun getParams(): UsersQueryParams
    suspend fun saveUser(userModel: UserModel): SaveUserResponse
    suspend fun deleteUser(userModel: UserModel): DeleteUserResponse
    suspend fun resetPassword(email: String): Result<Boolean>
    suspend fun getUser(uid: String): UserResponse
    suspend fun getUsersByEmail(email: String): Result<List<UserModel>>
    suspend fun getUsersByName(name: String): Result<List<UserModel>>
    suspend fun registerUser(email: String, password: String, displayName: String): UserResponse
    suspend fun authenticateUser(email: String, password: String): UserResponse
    suspend fun getAllUsers() : Result<List<UserModel>>
    suspend fun getReportedUsers() : Result<List<UserModel>>
    fun getUsersPaging(pagingReference: Flow<Int>) : Flow<List<UserModel>>
    suspend fun blockUser(user: RemovedUser) : Result<Boolean>
    suspend fun getBlockStatus(id: String) : Result<Boolean>
    suspend fun unblockUser(id: String) : Result<Boolean>
}