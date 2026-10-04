package io.github.xgl34222220.hetu;

import android.app.Application;
import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import java.io.File;
import java.nio.file.Files;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=Application.class)
public class AppCrashReportTest {
    @Test public void capturesSourceLocationAndCauseWithoutMessageSecrets() throws Exception {
        Context context=ApplicationProvider.getApplicationContext();
        AppCrashReport.screen("Tools/files");
        Throwable cause=new SecurityException("token=private subscription");
        Throwable error=new IllegalStateException("https://private.example/sub?secret=123",cause);
        AppCrashReport.save(context,Thread.currentThread(),error);
        String report=AppCrashReport.read(context);
        assertTrue(report.contains("Tools/files"));
        assertTrue(report.contains("java.lang.SecurityException"));
        assertTrue(report.contains("AppCrashReportTest.java:"));
        assertFalse(report.contains("private.example"));
        assertFalse(report.contains("token=private"));
    }
    @Test public void overwritesPreviousCrashAndBoundsStoredSize() throws Exception {
        Context context=ApplicationProvider.getApplicationContext();
        StackTraceElement[] trace=new StackTraceElement[2000];
        java.util.Arrays.fill(trace,new StackTraceElement("VeryLong".repeat(500),"method","Source.java",123));
        Throwable error=new RuntimeException("message");error.setStackTrace(trace);
        AppCrashReport.save(context,Thread.currentThread(),error);
        assertTrue(new File(context.getFilesDir(),"last-app-crash.txt").length()<=16384);
        AppCrashReport.save(context,Thread.currentThread(),new NullPointerException("latest"));
        assertTrue(AppCrashReport.read(context).contains("java.lang.NullPointerException"));
        assertFalse(AppCrashReport.read(context).contains("VeryLong"));
    }
    @Test public void recordingFailureStillDelegatesToAndroidCrashHandler() throws Exception {
        Context context=ApplicationProvider.getApplicationContext();
        File file=new File(context.getFilesDir(),"last-app-crash.txt");
        Files.deleteIfExists(file.toPath());assertTrue(file.mkdir());
        final Throwable[] delivered=new Throwable[1];
        Throwable error=new IllegalArgumentException("real failure");
        try {
            AppCrashReport.handler(context,(thread,failure)->delivered[0]=failure).uncaughtException(Thread.currentThread(),error);
            assertSame(error,delivered[0]);
        } finally { assertTrue(file.delete()); }
    }
}
