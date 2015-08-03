/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.goanna.sync.repositories.android;

import org.mozilla.goanna.sync.repositories.HistoryRepository;
import org.mozilla.goanna.sync.repositories.delegates.RepositorySessionCreationDelegate;

import android.content.Context;

public class AndroidBrowserHistoryRepository extends AndroidBrowserRepository implements HistoryRepository {

  @Override
  protected void sessionCreator(RepositorySessionCreationDelegate delegate, Context context) {
    AndroidBrowserHistoryRepositorySession session = new AndroidBrowserHistoryRepositorySession(AndroidBrowserHistoryRepository.this, context);
    delegate.onSessionCreated(session);
  }

  @Override
  protected AndroidBrowserRepositoryDataAccessor getDataAccessor(Context context) {
    return new AndroidBrowserHistoryDataAccessor(context);
  }

}
