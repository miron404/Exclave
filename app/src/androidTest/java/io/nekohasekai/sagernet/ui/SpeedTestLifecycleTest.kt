package io.nekohasekai.sagernet.ui

import android.R
import androidx.fragment.app.Fragment
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SpeedTestLifecycleTest {
    @Test
    fun destroyingTheFragmentViewCancelsTheDialogWork() {
        val started = CountDownLatch(1)
        val canceled = CountDownLatch(1)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val fragment = Fragment(R.layout.simple_list_item_1)
                activity.supportFragmentManager.beginTransaction()
                    .add(R.id.content, fragment, "speed-test-lifecycle")
                    .commitNow()
                SpeedTestDialog(fragment).start {
                    try {
                        started.countDown()
                        awaitCancellation()
                    } finally {
                        canceled.countDown()
                    }
                }
            }
            assertTrue("The dialog did not start its task", started.await(10, TimeUnit.SECONDS))
            scenario.onActivity { activity ->
                val fragment = activity.supportFragmentManager.findFragmentByTag("speed-test-lifecycle")!!
                activity.supportFragmentManager.beginTransaction().remove(fragment).commitNow()
            }
            assertTrue("Work survived destruction of the view", canceled.await(10, TimeUnit.SECONDS))
        }
    }
}
