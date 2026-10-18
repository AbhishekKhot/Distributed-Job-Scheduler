plugins {
    id("kairos.library-conventions")
}

description = "Password hashing, access and refresh tokens, API keys, secret encryption."

dependencies {
    api(project(":libs:kairos-domain"))
}
