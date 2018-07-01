/* -*- Mode: Java; c-basic-offset: 4; tab-width: 20; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.goanna;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.mozilla.goanna.GoannaProfileDirectories.NoMozillaDirectoryException;
import org.mozilla.goanna.GoannaProfileDirectories.NoSuchProfileException;
import org.mozilla.goanna.db.BrowserDB;
import org.mozilla.goanna.db.LocalBrowserDB;
import org.mozilla.goanna.db.StubBrowserDB;
import org.mozilla.goanna.distribution.Distribution;
import org.mozilla.goanna.mozglue.ContextUtils;
import org.mozilla.goanna.mozglue.RobocopTarget;
import org.mozilla.goanna.firstrun.FirstrunPane;
import org.mozilla.goanna.util.INIParser;
import org.mozilla.goanna.util.INISection;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

public final class GoannaProfile {
    private static final String LOGTAG = "GoannaProfile";

    // Only tests should need to do this.
    // We can default this to AppConstants.RELEASE_BUILD once we fix Bug 1069687.
    private static volatile boolean sAcceptDirectoryChanges = true;

    @RobocopTarget
    public static void enableDirectoryChanges() {
        Log.w(LOGTAG, "Directory changes should only be enabled for tests. And even then it's a bad idea.");
        sAcceptDirectoryChanges = true;
    }

    // Used to "lock" the guest profile, so that we'll always restart in it
    private static final String LOCK_FILE_NAME = ".active_lock";
    public static final String DEFAULT_PROFILE = "default";
    public static final String GUEST_PROFILE = "guest";

    private static final HashMap<String, GoannaProfile> sProfileCache = new HashMap<String, GoannaProfile>();
    private static String sDefaultProfileName;

    // Caches the guest profile dir.
    private static File sGuestDir;
    private static GoannaProfile sGuestProfile;

    public static boolean sIsUsingCustomProfile;

    private final String mName;
    private final File mMozillaDir;
    private final boolean mIsWebAppProfile;
    private final Context mApplicationContext;

    private final BrowserDB mDB;

    /**
     * Access to this member should be synchronized to avoid
     * races during creation -- particularly between getDir and GoannaView#init.
     *
     * Not final because this is lazily computed. 
     */
    private File mProfileDir;

    // Caches whether or not a profile is "locked".
    // Only used by the guest profile to determine if it should be reused or
    // deleted on startup.
    // These are volatile for an incremental improvement in thread safety,
    // but this is not a complete solution for concurrency.
    private volatile LockState mLocked = LockState.UNDEFINED;
    private volatile boolean mInGuestMode;

    // Constants to cache whether or not a profile is "locked".
    private enum LockState {
        LOCKED,
        UNLOCKED,
        UNDEFINED
    };

    /**
     * Warning: has a side-effect of setting sIsUsingCustomProfile.
     * Can return null.
     */
    public static GoannaProfile getFromArgs(final Context context, final String args) {
        if (args == null) {
            return null;
        }

        String profileName = null;
        String profilePath = null;
        if (args.contains("-P")) {
            final Pattern p = Pattern.compile("(?:-P\\s*)(\\w*)(\\s*)");
            final Matcher m = p.matcher(args);
            if (m.find()) {
                profileName = m.group(1);
            }
        }

        if (args.contains("-profile")) {
            final Pattern p = Pattern.compile("(?:-profile\\s*)(\\S*)(\\s*)");
            final Matcher m = p.matcher(args);
            if (m.find()) {
                profilePath =  m.group(1);
            }

            if (profileName == null) {
                profileName = GoannaProfile.DEFAULT_PROFILE;
            }

            GoannaProfile.sIsUsingCustomProfile = true;
        }

        if (profileName == null && profilePath == null) {
            return null;
        }

        return GoannaProfile.get(context, profileName, profilePath);
    }

    public static GoannaProfile get(Context context) {
        boolean isGoannaApp = false;
        try {
            isGoannaApp = context instanceof GoannaApp;
        } catch (NoClassDefFoundError ex) {}

        if (isGoannaApp) {
            // Check for a cached profile on this context already
            // TODO: We should not be caching profile information on the Activity context
            final GoannaApp goannaApp = (GoannaApp) context;
            if (goannaApp.mProfile != null) {
                return goannaApp.mProfile;
            }
        }

        final String args;
        if (context instanceof Activity) {
            args = ContextUtils.getStringExtra(((Activity) context).getIntent(), "args");
        } else {
            args = null;
        }

        if (GuestSession.shouldUse(context, args)) {
            final GoannaProfile p = GoannaProfile.getOrCreateGuestProfile(context);
            if (isGoannaApp) {
                ((GoannaApp) context).mProfile = p;
            }
            return p;
        }

        final GoannaProfile fromArgs = GoannaProfile.getFromArgs(context, args);
        if (fromArgs != null) {
            if (isGoannaApp) {
                ((GoannaApp) context).mProfile = fromArgs;
            }
            return fromArgs;
        }

        if (isGoannaApp) {
            final GoannaApp goannaApp = (GoannaApp) context;
            String defaultProfileName;
            try {
                defaultProfileName = goannaApp.getDefaultProfileName();
            } catch (NoMozillaDirectoryException e) {
                // If this failed, we're screwed. But there are so many callers that
                // we'll just throw a RuntimeException.
                Log.wtf(LOGTAG, "Unable to get default profile name.", e);
                throw new RuntimeException(e);
            }
            // Otherwise, get the default profile for the Activity.
            return get(context, defaultProfileName);
        }

        return get(context, "");
    }

    public static GoannaProfile get(Context context, String profileName) {
        synchronized (sProfileCache) {
            GoannaProfile profile = sProfileCache.get(profileName);
            if (profile != null)
                return profile;
        }
        return get(context, profileName, (File)null);
    }

    @RobocopTarget
    public static GoannaProfile get(Context context, String profileName, String profilePath) {
        File dir = null;
        if (!TextUtils.isEmpty(profilePath)) {
            dir = new File(profilePath);
            if (!dir.exists() || !dir.isDirectory()) {
                Log.w(LOGTAG, "requested profile directory missing: " + profilePath);
            }
        }
        return get(context, profileName, dir);
    }

    // Extension hook.
    private static volatile BrowserDB.Factory sDBFactory;
    public static void setBrowserDBFactory(BrowserDB.Factory factory) {
        sDBFactory = factory;
    }

    @RobocopTarget
    public static GoannaProfile get(Context context, String profileName, File profileDir) {
        if (sDBFactory == null) {
            // We do this so that GoannaView consumers don't need to know anything about BrowserDB.
            // It's a bit of a broken abstraction, but very tightly coupled, so we work around it
            // for now. We can't just have GoannaView set this, because then it would collide in
            // Fennec's use of GoannaView.
            // We should never see this in Fennec itself, because GoannaApplication sets the factory
            // in onCreate.
            Log.d(LOGTAG, "Defaulting to StubBrowserDB.");
            sDBFactory = StubBrowserDB.getFactory();
        }
        return GoannaProfile.get(context, profileName, profileDir, sDBFactory);
    }

    // Note that the profile cache respects only the profile name!
    // If the directory changes, the returned GoannaProfile instance will be mutated.
    // If the factory differs, it will be *ignored*.
    public static GoannaProfile get(Context context, String profileName, File profileDir, BrowserDB.Factory dbFactory) {
        Log.v(LOGTAG, "Fetching profile: '" + profileName + "', '" + profileDir + "'");
        if (context == null) {
            throw new IllegalArgumentException("context must be non-null");
        }

        // If no profile was passed in, look for the default profile listed in profiles.ini.
        // If that doesn't exist, look for a profile called 'default'.
        if (TextUtils.isEmpty(profileName) && profileDir == null) {
            try {
                profileName = GoannaProfile.getDefaultProfileName(context);
            } catch (NoMozillaDirectoryException e) {
                // We're unable to do anything sane here.
                throw new RuntimeException(e);
            }
        }

        // Actually try to look up the profile.
        synchronized (sProfileCache) {
            GoannaProfile profile = sProfileCache.get(profileName);
            if (profile == null) {
                try {
                    profile = new GoannaProfile(context, profileName, profileDir, dbFactory);
                } catch (NoMozillaDirectoryException e) {
                    // We're unable to do anything sane here.
                    throw new RuntimeException(e);
                }
                sProfileCache.put(profileName, profile);
                return profile;
            }

            if (profileDir == null) {
                // Fine.
                return profile;
            }

            if (profile.getDir().equals(profileDir)) {
                // Great! We're consistent.
                return profile;
            }

            if (sAcceptDirectoryChanges) {
                if (AppConstants.RELEASE_BUILD) {
                    Log.e(LOGTAG, "Release build trying to switch out profile dir. This is an error, but let's do what we can.");
                }
                profile.setDir(profileDir);
                return profile;
            }

            throw new IllegalStateException("Refusing to reuse profile with a different directory.");
        }
    }

    public static boolean removeProfile(Context context, String profileName) {
        if (profileName == null) {
            Log.w(LOGTAG, "Unable to remove profile: null profile name.");
            return false;
        }

        final GoannaProfile profile = get(context, profileName);
        if (profile == null) {
            return false;
        }
        final boolean success = profile.remove();

        if (success) {
            // Clear all shared prefs for the given profile.
            GoannaSharedPrefs.forProfileName(context, profileName)
                            .edit().clear().apply();
        }

        return success;
    }

    // Only public for access from tests.
    @RobocopTarget
    public static GoannaProfile createGuestProfile(Context context) {
        try {
            // We need to force the creation of a new guest profile if we want it outside of the normal profile path,
            // otherwise GoannaProfile.getDir will try to be smart and build it for us in the normal profiles dir.
            getGuestDir(context).mkdir();
            GoannaProfile profile = getGuestProfile(context);

            // If we're creating this guest session over the keyguard, don't lock it.
            // This will force the guest session to exit if the user unlocks their phone
            // and starts Fennec.
            profile.lock();

            /*
             * Now do the things that createProfileDirectory normally does --
             * right now that's kicking off DB init.
             */
            profile.enqueueInitialization(profile.getDir());

            return profile;
        } catch (Exception ex) {
            Log.e(LOGTAG, "Error creating guest profile", ex);
        }
        return null;
    }

    public static void leaveGuestSession(Context context) {
        GoannaProfile profile = getGuestProfile(context);
        if (profile != null) {
            profile.unlock();
        }
    }

    private static File getGuestDir(Context context) {
        if (sGuestDir == null) {
            sGuestDir = context.getFileStreamPath("guest");
        }
        return sGuestDir;
    }

    /**
     * Performs IO. Be careful of using this on the main thread.
     */
    public static GoannaProfile getOrCreateGuestProfile(Context context) {
        GoannaProfile p = getGuestProfile(context);
        if (p == null) {
            return createGuestProfile(context);
        }

        return p;
    }

    public static GoannaProfile getGuestProfile(Context context) {
        if (sGuestProfile == null) {
            File guestDir = getGuestDir(context);
            if (guestDir.exists()) {
                sGuestProfile = get(context, GUEST_PROFILE, guestDir);
                sGuestProfile.mInGuestMode = true;
            }
        }

        return sGuestProfile;
    }

    public static boolean maybeCleanupGuestProfile(final Context context) {
        final GoannaProfile profile = getGuestProfile(context);
        if (profile == null) {
            return false;
        }

        if (!profile.locked()) {
            profile.mInGuestMode = false;

            // If the guest dir exists, but it's unlocked, delete it
            removeGuestProfile(context);

            return true;
        }
        return false;
    }

    private static void removeGuestProfile(Context context) {
        boolean success = false;
        try {
            File guestDir = getGuestDir(context);
            if (guestDir.exists()) {
                success = delete(guestDir);
            }
        } catch (Exception ex) {
            Log.e(LOGTAG, "Error removing guest profile", ex);
        }

        if (success) {
            // Clear all shared prefs for the guest profile.
            GoannaSharedPrefs.forProfileName(context, GUEST_PROFILE)
                            .edit().clear().apply();
        }
    }

    public static boolean delete(File file) throws IOException {
        // Try to do a quick initial delete
        if (file.delete())
            return true;

        if (file.isDirectory()) {
            // If the quick delete failed and this is a dir, recursively delete the contents of the dir
            String files[] = file.list();
            for (String temp : files) {
                File fileDelete = new File(file, temp);
                delete(fileDelete);
            }
        }

        // Even if this is a dir, it should now be empty and delete should work
        return file.delete();
    }

    private GoannaProfile(Context context, String profileName, File profileDir, BrowserDB.Factory dbFactory) throws NoMozillaDirectoryException {
        if (TextUtils.isEmpty(profileName)) {
            throw new IllegalArgumentException("Unable to create GoannaProfile for empty profile name.");
        }

        mApplicationContext = context.getApplicationContext();
        mName = profileName;
        mIsWebAppProfile = profileName.startsWith("webapp");
        mMozillaDir = GoannaProfileDirectories.getMozillaDirectory(context);

        // This apes the behavior of setDir.
        if (profileDir != null && profileDir.exists() && profileDir.isDirectory()) {
            mProfileDir = profileDir;
        }

        // N.B., mProfileDir can be null at this point.
        mDB = dbFactory.get(profileName, mProfileDir);
    }

    public BrowserDB getDB() {
        return mDB;
    }

    // Warning, Changing the lock file state from outside apis will cause this to become out of sync
    public boolean locked() {
        if (mLocked != LockState.UNDEFINED) {
            return mLocked == LockState.LOCKED;
        }

        boolean profileExists;
        synchronized (this) {
            profileExists = mProfileDir != null && mProfileDir.exists();
        }

        // Don't use getDir() as it will create a dir if none exists.
        if (profileExists) {
            File lockFile = new File(mProfileDir, LOCK_FILE_NAME);
            boolean res = lockFile.exists();
            mLocked = res ? LockState.LOCKED : LockState.UNLOCKED;
        } else {
            mLocked = LockState.UNLOCKED;
        }

        return mLocked == LockState.LOCKED;
    }

    public boolean lock() {
        try {
            // If this dir doesn't exist getDir will create it for us
            final File lockFile = new File(getDir(), LOCK_FILE_NAME);
            final boolean result = lockFile.createNewFile();
            if (lockFile.exists()) {
                mLocked = LockState.LOCKED;
            } else {
                mLocked = LockState.UNLOCKED;
            }
            return result;
        } catch(IOException ex) {
            Log.e(LOGTAG, "Error locking profile", ex);
        }
        mLocked = LockState.UNLOCKED;
        return false;
    }

    public boolean unlock() {
        final File profileDir;
        synchronized (this) {
            // Don't use getDir() as it will create a dir.
            profileDir = mProfileDir;
        }

        if (profileDir == null || !profileDir.exists()) {
            return true;
        }

        try {
            final File lockFile = new File(profileDir, LOCK_FILE_NAME);
            if (!lockFile.exists()) {
                mLocked = LockState.UNLOCKED;
                return true;
            }

            final boolean result = delete(lockFile);
            if (result) {
                mLocked = LockState.UNLOCKED;
            } else {
                mLocked = LockState.LOCKED;
            }
            return result;
        } catch(IOException ex) {
            Log.e(LOGTAG, "Error unlocking profile", ex);
        }

        mLocked = LockState.LOCKED;
        return false;
    }

    public boolean inGuestMode() {
        return mInGuestMode;
    }

    private void setDir(File dir) {
        if (dir != null && dir.exists() && dir.isDirectory()) {
            synchronized (this) {
                mProfileDir = dir;
            }
        }
    }

    public String getName() {
        return mName;
    }

    public synchronized File getDir() {
        forceCreate();
        return mProfileDir;
    }

    public synchronized GoannaProfile forceCreate() {
        if (mProfileDir != null) {
            return this;
        }

        try {
            // Check if a profile with this name already exists.
            try {
                mProfileDir = findProfileDir();
                Log.d(LOGTAG, "Found profile dir.");
            } catch (NoSuchProfileException noSuchProfile) {
                // If it doesn't exist, create it.
                mProfileDir = createProfileDir();
            }
        } catch (IOException ioe) {
            Log.e(LOGTAG, "Error getting profile dir", ioe);
        }
        return this;
    }

    public File getFile(String aFile) {
        File f = getDir();
        if (f == null)
            return null;

        return new File(f, aFile);
    }

    /**
     * Moves the session file to the backup session file.
     *
     * sessionstore.js should hold the current session, and sessionstore.bak
     * should hold the previous session (where it is used to read the "tabs
     * from last time"). Normally, sessionstore.js is moved to sessionstore.bak
     * on a clean quit, but this doesn't happen if Fennec crashed. Thus, this
     * method should be called after a crash so sessionstore.bak correctly
     * holds the previous session.
     */
    public void moveSessionFile() {
        File sessionFile = getFile("sessionstore.js");
        if (sessionFile != null && sessionFile.exists()) {
            File sessionFileBackup = getFile("sessionstore.bak");
            sessionFile.renameTo(sessionFileBackup);
        }
    }

    /**
     * Get the string from a session file.
     *
     * The session can either be read from sessionstore.js or sessionstore.bak.
     * In general, sessionstore.js holds the current session, and
     * sessionstore.bak holds the previous session.
     *
     * @param readBackup if true, the session is read from sessionstore.bak;
     *                   otherwise, the session is read from sessionstore.js
     *
     * @return the session string
     */
    public String readSessionFile(boolean readBackup) {
        File sessionFile = getFile(readBackup ? "sessionstore.bak" : "sessionstore.js");

        try {
            if (sessionFile != null && sessionFile.exists()) {
                return readFile(sessionFile);
            }
        } catch (IOException ioe) {
            Log.e(LOGTAG, "Unable to read session file", ioe);
        }
        return null;
    }

    public String readFile(String filename) throws IOException {
        File dir = getDir();
        if (dir == null) {
            throw new IOException("No profile directory found");
        }
        File target = new File(dir, filename);
        return readFile(target);
    }

    private String readFile(File target) throws IOException {
        FileReader fr = new FileReader(target);
        try {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            int read = fr.read(buf);
            while (read >= 0) {
                sb.append(buf, 0, read);
                read = fr.read(buf);
            }
            return sb.toString();
        } finally {
            fr.close();
        }
    }

    private boolean remove() {
        try {
            synchronized (this) {
                final File dir = getDir();
                if (dir.exists()) {
                    delete(dir);
                }

                try {
                    mProfileDir = findProfileDir();
                } catch (NoSuchProfileException noSuchProfile) {
                    // If the profile doesn't exist, there's nothing left for us to do.
                    return false;
                }
            }

            final INIParser parser = GoannaProfileDirectories.getProfilesINI(mMozillaDir);
            final Hashtable<String, INISection> sections = parser.getSections();
            for (Enumeration<INISection> e = sections.elements(); e.hasMoreElements();) {
                final INISection section = e.nextElement();
                String name = section.getStringProperty("Name");

                if (name == null || !name.equals(mName)) {
                    continue;
                }

                if (section.getName().startsWith("Profile")) {
                    // ok, we have stupid Profile#-named things.  Rename backwards.
                    try {
                        int sectionNumber = Integer.parseInt(section.getName().substring("Profile".length()));
                        String curSection = "Profile" + sectionNumber;
                        String nextSection = "Profile" + (sectionNumber+1);

                        sections.remove(curSection);

                        while (sections.containsKey(nextSection)) {
                            parser.renameSection(nextSection, curSection);
                            sectionNumber++;

                            curSection = nextSection;
                            nextSection = "Profile" + (sectionNumber+1);
                        }
                    } catch (NumberFormatException nex) {
                        // uhm, malformed Profile thing; we can't do much.
                        Log.e(LOGTAG, "Malformed section name in profiles.ini: " + section.getName());
                        return false;
                    }
                } else {
                    // this really shouldn't be the case, but handle it anyway
                    parser.removeSection(mName);
                }

                break;
            }

            parser.write();
            return true;
        } catch (IOException ex) {
            Log.w(LOGTAG, "Failed to remove profile.", ex);
            return false;
        }
    }

    /**
     * @return the default profile name for this application, or
     *         {@link GoannaProfile#DEFAULT_PROFILE} if none could be found.
     *
     * @throws NoMozillaDirectoryException
     *             if the Mozilla directory did not exist and could not be
     *             created.
     */
    public static String getDefaultProfileName(final Context context) throws NoMozillaDirectoryException {
        // Have we read the default profile from the INI already?
        // Changing the default profile requires a restart, so we don't
        // need to worry about runtime changes.
        if (sDefaultProfileName != null) {
            return sDefaultProfileName;
        }

        final String profileName = GoannaProfileDirectories.findDefaultProfileName(context);
        if (profileName == null) {
            // Note that we don't persist this back to profiles.ini.
            sDefaultProfileName = DEFAULT_PROFILE;
            return DEFAULT_PROFILE;
        }

        sDefaultProfileName = profileName;
        return sDefaultProfileName;
    }

    private File findProfileDir() throws NoSuchProfileException {
        return GoannaProfileDirectories.findProfileDir(mMozillaDir, mName);
    }

    private File createProfileDir() throws IOException {
        INIParser parser = GoannaProfileDirectories.getProfilesINI(mMozillaDir);

        // Salt the name of our requested profile
        String saltedName = GoannaProfileDirectories.saltProfileName(mName);
        File profileDir = new File(mMozillaDir, saltedName);
        while (profileDir.exists()) {
            saltedName = GoannaProfileDirectories.saltProfileName(mName);
            profileDir = new File(mMozillaDir, saltedName);
        }

        // Attempt to create the salted profile dir
        if (!profileDir.mkdirs()) {
            throw new IOException("Unable to create profile.");
        }
        Log.d(LOGTAG, "Created new profile dir.");

        // Now update profiles.ini
        // If this is the first time its created, we also add a General section
        // look for the first profile number that isn't taken yet
        int profileNum = 0;
        boolean isDefaultSet = false;
        INISection profileSection;
        while ((profileSection = parser.getSection("Profile" + profileNum)) != null) {
            profileNum++;
            if (profileSection.getProperty("Default") != null) {
                isDefaultSet = true;
            }
        }

        profileSection = new INISection("Profile" + profileNum);
        profileSection.setProperty("Name", mName);
        profileSection.setProperty("IsRelative", 1);
        profileSection.setProperty("Path", saltedName);

        if (parser.getSection("General") == null) {
            INISection generalSection = new INISection("General");
            generalSection.setProperty("StartWithLastProfile", 1);
            parser.addSection(generalSection);
        }

        if (!isDefaultSet && !mIsWebAppProfile) {
            // only set as default if this is the first non-webapp
            // profile we're creating
            profileSection.setProperty("Default", 1);

            // We have no intention of stopping this session. The FIRSTRUN session
            // ends when the browsing session/activity has ended. All events
            // during firstrun will be tagged as FIRSTRUN.
            Telemetry.startUISession(TelemetryContract.Session.FIRSTRUN);
        }

        parser.addSection(profileSection);
        parser.write();

        // Trigger init for non-webapp profiles.
        if (!mIsWebAppProfile) {
            enqueueInitialization(profileDir);
        }

        // Write out profile creation time, mirroring the logic in nsToolkitProfileService.
        try {
            FileOutputStream stream = new FileOutputStream(profileDir.getAbsolutePath() + File.separator + "times.json");
            OutputStreamWriter writer = new OutputStreamWriter(stream, Charset.forName("UTF-8"));
            try {
                writer.append("{\"created\": " + System.currentTimeMillis() + "}\n");
            } finally {
                writer.close();
            }
        } catch (Exception e) {
            // Best-effort.
            Log.w(LOGTAG, "Couldn't write times.json.", e);
        }

        // Initialize pref flag for displaying the start pane for a new non-webapp profile.
        if (!mIsWebAppProfile) {
            final SharedPreferences prefs = GoannaSharedPrefs.forProfile(mApplicationContext);
            prefs.edit().putBoolean(FirstrunPane.PREF_FIRSTRUN_ENABLED, true).apply();
        }

        return profileDir;
    }

    /**
     * This method is called once, immediately before creation of the profile
     * directory completes.
     *
     * It queues up work to be done in the background to prepare the profile,
     * such as adding default bookmarks.
     *
     * This is public for use *from tests only*!
     */
    @RobocopTarget
    public void enqueueInitialization(final File profileDir) {
        Log.i(LOGTAG, "Enqueuing profile init.");
        final Context context = mApplicationContext;

        // Add everything when we're done loading the distribution.
        final Distribution distribution = Distribution.getInstance(context);
        distribution.addOnDistributionReadyCallback(new Distribution.ReadyCallback() {
            @Override
            public void distributionNotFound() {
                this.distributionFound(null);
            }

            @Override
            public void distributionFound(Distribution distribution) {
                Log.d(LOGTAG, "Running post-distribution task: bookmarks.");

                final ContentResolver cr = context.getContentResolver();

                // Because we are running in the background, we want to synchronize on the
                // GoannaProfile instance so that we don't race with main thread operations
                // such as locking/unlocking/removing the profile.
                synchronized (GoannaProfile.this) {
                    // Skip initialization if the profile directory has been removed.
                    if (!profileDir.exists()) {
                        return;
                    }

                    // We pass the number of added bookmarks to ensure that the
                    // indices of the distribution and default bookmarks are
                    // contiguous. Because there are always at least as many
                    // bookmarks as there are favicons, we can also guarantee that
                    // the favicon IDs won't overlap.
                    final LocalBrowserDB db = new LocalBrowserDB(getName());
                    final int offset = distribution == null ? 0 : db.addDistributionBookmarks(cr, distribution, 0);
                    db.addDefaultBookmarks(context, cr, offset);
                }
            }

            @Override
            public void distributionArrivedLate(Distribution distribution) {
                Log.d(LOGTAG, "Running late distribution task: bookmarks.");
                // Recover as best we can.
                synchronized (GoannaProfile.this) {
                    // Skip initialization if the profile directory has been removed.
                    if (!profileDir.exists()) {
                        return;
                    }

                    final LocalBrowserDB db = new LocalBrowserDB(getName());
                    // We assume we've been called very soon after startup, and so our offset
                    // into "Mobile Bookmarks" is the number of bookmarks in the DB.
                    final ContentResolver cr = context.getContentResolver();
                    final int offset = db.getCount(cr, "bookmarks");
                    db.addDistributionBookmarks(cr, distribution, offset);
                }
            }
        });
    }
}