package com.neitex

class AuthorizationUnsuccessful : Exception()

class SchoolsByUnavailable(msg: String, cause: Throwable) : Exception(msg, cause)

class BadSchoolsByCredentials : Exception()

class PageNotFound(msg: String) : Exception(msg)
