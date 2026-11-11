package com.sunloom.app.update

/** The GitHub build checks GitHub's releases when the person asks; a sideloaded app is not updated by a store. */
fun updateCheckFeature(): UpdateCheckFeature? = GithubUpdateChecker()
