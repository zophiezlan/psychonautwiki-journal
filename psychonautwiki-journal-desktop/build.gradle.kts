import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    jvm("desktop")
    
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
        freeCompilerArgs.add("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
    }
    
    sourceSets {
        val desktopMain by getting
        
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            
            // Coroutines
            implementation(libs.kotlinx.coroutines.core)
            
            // Serialization
            implementation(libs.kotlinx.serialization.json)
            
            // Navigation - handled manually for Compose Multiplatform
            // implementation(libs.navigation.compose)
            
            // Lifecycle
            implementation(libs.lifecycle.viewmodel)
            
            // Dependency Injection
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            
            // Database
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines.extensions)
            
            // DateTime
            implementation(libs.kotlinx.datetime)
        }
        
        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.sqldelight.sqlite.driver)
            implementation(libs.koin.core)
        }
        
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotest.framework.engine)
            implementation(libs.kotest.assertions.core)
            implementation(libs.mockk)
            implementation(libs.koin.test)
        }
        
        val desktopTest by getting {
            dependencies {
                implementation(libs.kotest.runner.junit5)
                implementation(libs.cucumber.java8)
                implementation(libs.cucumber.junit.platform.engine)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.isaakhanimann.journal.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb, TargetFormat.AppImage)
            packageName = "PsychonautWiki Journal"
            packageVersion = "1.0.0"
            description = "A safer way to track substance experiences"

            linux {
                iconFile.set(project.file("src/desktopMain/resources/icon.png"))
                packageName = "psychonautwiki-journal"
                debMaintainer = "opensource@psychonautwiki.org"
                menuGroup = "Utilities"
                appCategory = "Utility"
            }

            // -----------------------------------------------------------------
            // SIGNING — driven by environment variables so secrets never enter
            // version control. Builds without these env vars produce UNSIGNED
            // artefacts and SHOULD NOT be distributed to end users; see
            // RELEASE.md for the full release procedure including SHA-256
            // checksums and detached PGP signatures of the installer files.
            // -----------------------------------------------------------------
            macOS {
                // Required: APPLE_DEVELOPER_ID, e.g. "Developer ID Application: Foo (TEAMID)"
                val appleSigningIdentity: String? = System.getenv("APPLE_DEVELOPER_ID")
                if (!appleSigningIdentity.isNullOrBlank()) {
                    signing {
                        sign.set(true)
                        identity.set(appleSigningIdentity)
                    }
                    // Required for notarization: APPLE_ID, APPLE_TEAM_ID, APPLE_APP_SPECIFIC_PASSWORD
                    val appleId = System.getenv("APPLE_ID")
                    val appleTeamId = System.getenv("APPLE_TEAM_ID")
                    val applePassword = System.getenv("APPLE_APP_SPECIFIC_PASSWORD")
                    if (!appleId.isNullOrBlank() && !appleTeamId.isNullOrBlank() && !applePassword.isNullOrBlank()) {
                        notarization {
                            appleID.set(appleId)
                            teamID.set(appleTeamId)
                            password.set(applePassword)
                        }
                    }
                }
            }
            windows {
                // Optional: msiPackageVersion, productVersion etc. live here.
                // Authenticode signing of the resulting .msi is currently performed
                // post-build via signtool — see RELEASE.md. Once the Compose
                // Multiplatform plugin exposes a Windows signing block, wire it
                // up here using WIN_SIGN_PFX_PATH / WIN_SIGN_PFX_PASSWORD env vars.
            }
        }

        buildTypes.release.proguard {
            configurationFiles.from("proguard-rules.pro")
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

sqldelight {
    databases {
        create("Database") {
            packageName.set("com.isaakhanimann.journal.database")
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
        }
    }
}