plugins {
    id("kairos.library-conventions")
}

description = "PostgreSQL access: cell routing, repositories and set-based statements."

dependencies {
    api(project(":libs:kairos-domain"))
}
