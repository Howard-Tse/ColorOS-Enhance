package dev.howard.colorosenhance.updates;

import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongPredicate;

/** Main-thread state: one silent check per foreground visit, shared by all settings pages. */
public final class LaunchUpdateCheck {
    private final Executor worker, main;
    private final BooleanSupplier includePrereleases, promptsEnabled;
    private final LongPredicate skipped;
    private final Function<Boolean, GitHubUpdateClient.Result> job;
    private final long installedCode;
    private final Runnable changed;
    private boolean foreground, checked, running;
    private ReleaseInfo pending;

    public LaunchUpdateCheck(Executor worker, Executor main, BooleanSupplier includePrereleases,
                             BooleanSupplier promptsEnabled, LongPredicate skipped,
                             Function<Boolean, GitHubUpdateClient.Result> job, long installedCode, Runnable changed) {
        this.worker=worker; this.main=main; this.includePrereleases=includePrereleases;
        this.promptsEnabled=promptsEnabled; this.skipped=skipped;
        this.job=job; this.installedCode=installedCode; this.changed=changed;
    }
    public void foreground() {
        if(foreground)return;
        foreground=true; checked=false; pending=null;
    }
    public void background() {
        foreground=false; checked=false; pending=null; changed.run();
    }
    public void check() {
        if(!foreground || checked || !promptsEnabled.getAsBoolean())return;
        checked=true;
        // A quick exit/re-entry can reuse the still-running request rather than overlap requests.
        if(running)return;
        running=true;
        boolean preview = includePrereleases.getAsBoolean();
        try {
            worker.execute(()->{
                GitHubUpdateClient.Result result;
                try { result=job.apply(preview); } catch(RuntimeException unavailable) { result=null; }
                GitHubUpdateClient.Result completed=result;
                main.execute(()->complete(completed));
            });
        } catch(RuntimeException unavailable) { complete(null); }
    }
    private void complete(GitHubUpdateClient.Result result) {
        running=false;
        if(!foreground || !checked || !promptsEnabled.getAsBoolean())return;
        ReleaseInfo release=result==null?null:result.release();
        if(release!=null && release.newerThan(installedCode) && !skipped.test(release.versionCode())
                && (!release.prerelease() || includePrereleases.getAsBoolean())) {
            pending=release; changed.run();
        }
    }
    public ReleaseInfo pending() { return foreground?pending:null; }
    public void dismiss() { pending=null; changed.run(); }
}
