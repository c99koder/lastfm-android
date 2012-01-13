/***************************************************************************
 *   Copyright 2005-2009 Last.fm Ltd.                                      *
 *   Portions contributed by Casey Link, Lukasz Wisniewski,                *
 *   Mike Jennings, and Michael Novak Jr.                                  *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *                                                                         *
 *   This program is distributed in the hope that it will be useful,       *
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of        *
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the         *
 *   GNU General Public License for more details.                          *
 *                                                                         *
 *   You should have received a copy of the GNU General Public License     *
 *   along with this program; if not, write to the                         *
 *   Free Software Foundation, Inc.,                                       *
 *   51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.         *
 ***************************************************************************/
package fm.last.android.activity;

import java.io.IOException;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.provider.MediaStore;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.webkit.WebView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TabHost;
import android.widget.TextView;
import android.widget.AdapterView.OnItemClickListener;
import fm.last.android.Amazon;
import fm.last.android.AndroidLastFmServerFactory;
import fm.last.android.LastFMApplication;
import fm.last.android.R;
import fm.last.android.activity.Event.EventActivityResult;
import fm.last.android.adapter.EventListAdapter;
import fm.last.android.adapter.ListAdapter;
import fm.last.android.adapter.ListEntry;
import fm.last.android.adapter.NotificationAdapter;
import fm.last.android.player.IRadioPlayer;
import fm.last.android.player.RadioPlayerService;
import fm.last.android.utils.ImageCache;
import fm.last.android.utils.AsyncTaskEx;
import fm.last.api.Artist;
import fm.last.api.Event;
import fm.last.api.ImageUrl;
import fm.last.api.LastFmServer;
import fm.last.api.Tag;
import fm.last.api.User;
import fm.last.api.WSError;

/**
 * @author Jono Cole <jono@last.fm>
 * 
 */
public class Metadata extends Activity {

	private String mBio;
	private ListAdapter mSimilarAdapter;
	private ListAdapter mFanAdapter;
	private ListAdapter mTagAdapter;
	private String mArtistName = "";
	private String mTrackName = "";

	private BaseAdapter mEventAdapter;

	private ImageCache mImageCache;
	private EventActivityResult mOnEventActivityResult;
	private boolean mIsPlaying = false;

	TextView mTextView;
	TabHost mTabHost;
	WebView mWebView;
	ListView mSimilarList;
	ListView mTagList;
	ListView mFanList;
	ListView mEventList;
	ImageButton mOntourButton;
	LastFmServer mServer = AndroidLastFmServerFactory.getServer();

	public Metadata() {
		super();
	}

	@Override
	public void onCreate(Bundle icicle) {
		super.onCreate(icicle);
		setContentView(R.layout.metadata);

		if(getIntent().getData() != null) {
			if(getIntent().getData().getScheme().equals("http")) {
				List<String> segments = getIntent().getData().getPathSegments();
				
				mArtistName = Uri.decode(segments.get(1)).replace("+", " ");
				if(segments.size() > 3)
					mTrackName = Uri.decode(segments.get(3)).replace("+", " ");
			}
		} 
		else if(getIntent().getAction() != null) {
			if (getIntent().getAction().equals(MediaStore.INTENT_ACTION_MEDIA_SEARCH)) {
				mArtistName = getIntent().getStringExtra(MediaStore.EXTRA_MEDIA_ARTIST);
			}
		} 
		else {
			mArtistName = getIntent().getStringExtra("artist");
			mTrackName = getIntent().getStringExtra("track");
		}

		mTabHost = (TabHost)findViewById(R.id.TabBar);
		mTabHost.setup();

		mWebView = (WebView) findViewById(R.id.webview);
		mSimilarList = (ListView) findViewById(R.id.similar_list_view);
		mTagList = (ListView) findViewById(R.id.tags_list_view);
		mFanList = (ListView) findViewById(R.id.listeners_list_view);
		mEventList = (ListView) findViewById(R.id.events_list_view);
		mOntourButton = (ImageButton) findViewById(R.id.ontour);
		mOntourButton.setOnClickListener(mOntourListener);

		mTabHost.addTab(mTabHost.newTabSpec("bio")
                .setIndicator(getString(R.string.metadata_bio), getResources().getDrawable(R.drawable.ic_tab_bio))
                .setContent(R.id.webview));
		mTabHost.addTab(mTabHost.newTabSpec("similar")
                .setIndicator(getString(R.string.metadata_similar), getResources().getDrawable(R.drawable.ic_tab_similar_artists))
                .setContent(R.id.similar_list_view));
		if(RadioPlayerService.radioAvailable(this)) {
			mTabHost.addTab(mTabHost.newTabSpec("tags")
	                .setIndicator(getString(R.string.metadata_tags), getResources().getDrawable(R.drawable.ic_tab_tags))
	                .setContent(R.id.tags_list_view));
		} else {
			findViewById(R.id.tags_list_view).setVisibility(View.GONE);
		}
		mTabHost.addTab(mTabHost.newTabSpec("events")
                .setIndicator(getString(R.string.metadata_events), getResources().getDrawable(R.drawable.ic_tab_events))
                .setContent(R.id.events_list_view));
		mTabHost.addTab(mTabHost.newTabSpec("fans")
                .setIndicator(getString(R.string.metadata_Fans), getResources().getDrawable(R.drawable.ic_tab_top_listeners))
                .setContent(R.id.listeners_list_view));

		populateMetadata();

		if (getIntent().hasExtra("show_events"))
			mTabHost.setCurrentTabByTag("events");

		mIsPlaying = false;

		LastFMApplication.getInstance().bindService(new Intent(LastFMApplication.getInstance(), fm.last.android.player.RadioPlayerService.class),
				new ServiceConnection() {
					public void onServiceConnected(ComponentName comp, IBinder binder) {
						IRadioPlayer player = IRadioPlayer.Stub.asInterface(binder);
						try {
							mIsPlaying = player.isPlaying();
						} catch (RemoteException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
						try {
							LastFMApplication.getInstance().unbindService(this);
						} catch (IllegalArgumentException e) {
						}
					}

					public void onServiceDisconnected(ComponentName comp) {
					}
				}, 0);

	}

	@Override
	public void onResume() {
		super.onResume();
		try {
			LastFMApplication.getInstance().tracker.trackPageView("/Metadata");
		} catch (Exception e) {
			//Google Analytics doesn't appear to be thread safe
		}
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.player, menu);

		MenuItem changeView = menu.findItem(R.id.info_menu_item);
		changeView.setTitle(getString(R.string.action_nowplaying));
		changeView.setIcon(R.drawable.view_artwork);
		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		menu.findItem(R.id.buy_menu_item).setEnabled(Amazon.getAmazonVersion(this) > 0);
		menu.findItem(R.id.info_menu_item).setEnabled(mIsPlaying);

		return super.onPrepareOptionsMenu(menu);
	}

	private View.OnClickListener mOntourListener = new View.OnClickListener() {

		public void onClick(View v) {
			try {
				LastFMApplication.getInstance().tracker.trackEvent("Clicks", // Category
						"on-tour-badge", // Action
						"", // Label
						0); // Value
			} catch (Exception e) {
				//Google Analytics doesn't appear to be thread safe
			}
			mTabHost.setCurrentTabByTag("events");		
		}

	};
	
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		Intent intent;

		switch (item.getItemId()) {
		case R.id.info_menu_item:
			Intent i = new Intent(this, Player.class);
			startActivity(i);
			finish();
			break;
		case R.id.buy_menu_item:
			Amazon.searchForTrack(this, mArtistName, mTrackName);
			break;
		case R.id.share_menu_item:
			intent = new Intent(this, ShareResolverActivity.class);
			intent.putExtra(Share.INTENT_EXTRA_ARTIST, mArtistName);
			intent.putExtra(Share.INTENT_EXTRA_TRACK, mTrackName);
			startActivity(intent);
			break;
		case R.id.tag_menu_item:
			intent = new Intent(this, fm.last.android.activity.Tag.class);
			intent.putExtra("lastfm.artist", mArtistName);
			intent.putExtra("lastfm.track", mTrackName);
			startActivity(intent);
			break;
		default:
			break;
		}

		return super.onOptionsItemSelected(item);
	}

	private void populateMetadata() {

		// Order bio / event loading depending on whether
		// on tour button was clicked
		if (getIntent().hasExtra("show_events")) {
			new LoadEventsTask().execute((Void) null);
			new LoadBioTask().execute((Void) null);
		} else {
			new LoadBioTask().execute((Void) null);
			new LoadEventsTask().execute((Void) null);
		}

		new LoadSimilarTask().execute((Void) null);
		new LoadListenersTask().execute((Void) null);
		if(RadioPlayerService.radioAvailable(this))
			new LoadTagsTask().execute((Void) null);
		new LoadEventsTask().execute((Void) null);

		mTabHost.setCurrentTabByTag("bio");
	}

	private ImageCache getImageCache() {
		if (mImageCache == null) {
			mImageCache = new ImageCache();
		}
		return mImageCache;
	}

	private class LoadBioTask extends AsyncTaskEx<Void, Void, Boolean> {
		@Override
		public void onPreExecute() {
			mWebView.loadData(getString(R.string.common_loading), "text/html", "utf-8");
		}

		@Override
		public Boolean doInBackground(Void... params) {
			Artist artist;
			boolean success = false;

			if(mArtistName == null || LastFMApplication.getInstance().session == null)
				return false;
			
			try {
				String lang = Locale.getDefault().getLanguage();
				if (lang.equalsIgnoreCase("de")) {
					artist = mServer.getArtistInfo(mArtistName, null, lang, LastFMApplication.getInstance().session.getName());
				} else {
					artist = mServer.getArtistInfo(mArtistName, null, null, LastFMApplication.getInstance().session.getName());
				}
				if (artist.getBio().getContent() == null || artist.getBio().getContent().trim().length() == 0) {
					// no bio in current locale -> get the English bio
					artist = mServer.getArtistInfo(mArtistName, null, null, LastFMApplication.getInstance().session.getName());
				}
				String imageURL = "";
				for (ImageUrl image : artist.getImages()) {
					if (image.getSize().contentEquals("large")) {
						imageURL = image.getUrl();
						break;
					}
				}

				String listeners = "";
				String plays = "";
				String userplaycount = "";
				try {
					NumberFormat nf = NumberFormat.getInstance();
					listeners = nf.format(Integer.parseInt(artist.getListeners()));
					plays = nf.format(Integer.parseInt(artist.getPlaycount()));
					userplaycount = nf.format(Integer.parseInt(artist.getUserPlaycount()));
				} catch (NumberFormatException e) {
					e.printStackTrace();
				}

				String stationbuttonbg = "url(data:image/png;base64,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%3D%3D)";
				
				String stationbuttonmediumstyle = "color: white;"
						+ "cursor: pointer;"
						+ "display: block;"
						+ "font-size: 11px;"
						+ "font-weight: bold;"
						+ "height: 25px;"
						+ "line-height: 25px;"
						+ "margin: 0px 10px 10px 0px;"
						+ "max-width: 180px;"
						+ "text-decoration: none;"
						+ "overflow: hidden;"
						+ "padding-left: 30px;"
						+ "position: relative;"
						+ "background: " + stationbuttonbg + " top left no-repeat";

				String stationbuttonspanstyle = "position: relative;"
					+ "display: block;"
					+ "padding: 0 10px 0 2px;"
					+ "background: " + stationbuttonbg + " right top no-repeat;"
					+ "height: 25px;";
				
				mBio = "<html><body style='margin:0; padding:0; color:black; background: white; font-family: Helvetica; font-size: 11pt;'>"
						+ "<div style='padding:17px; margin:0; top:0px; left:0px; position:absolute;'>" + "<img src='" + imageURL
						+ "' style='margin-top: 4px; float: left; margin-right: 0px; margin-bottom: 14px; width:64px; border:1px solid gray; padding: 1px;'/>"
						+ "<div style='margin-left:84px; margin-top:3px'>" + "<span style='font-size: 15pt; font-weight:bold; padding:0px; margin:0px;'>"
						+ mArtistName + "</span><br/>" + "<span style='color:gray; font-weight: normal; font-size: 10pt;'>" + listeners + " "
						+ getString(R.string.metadata_listeners) + "<br/>" + plays + " " + getString(R.string.metadata_plays);
				if(userplaycount.length() > 0 && !userplaycount.equals("0"))
					mBio += "<br/>" + userplaycount + " " + getString(R.string.metadata_userplays);

				mBio += "</span>";

				if(RadioPlayerService.radioAvailable(Metadata.this))
					mBio += "<br/> <a style='"+ stationbuttonmediumstyle + "' href='lastfm://artist/" + Uri.encode(artist.getName()).replace("'", "%27") + "'>"
							+ "<span style='" + stationbuttonspanstyle + "'>Play " + artist.getName() + " Radio</span></a>";
				mBio += "</div><br style='clear:both;'/>" + formatBio(artist.getBio().getContent()) + "</div></body></html>";

				success = true;
			} catch (OutOfMemoryError e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			} catch (WSError e) {
				e.printStackTrace();
			}
			return success;
		}

		private String formatBio(String wikiText) {
			// last.fm api returns the wiki text without para formatting,
			// correct that:
			return wikiText.replaceAll("\\n+", "<br>");
		}

		@Override
		public void onPostExecute(Boolean result) {
			if (result) {
				try {
					mWebView.loadDataWithBaseURL(null, new String(mBio.getBytes(), "utf-8"), // need
																								// to
																								// do
																								// this,
																								// but
																								// is
																								// there
																								// a
																								// better
																								// way?
							"text/html", "utf-8", null);
					// request focus to make the web view immediately scrollable
					mWebView.requestFocus();
					return;
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
			mWebView.loadData(getString(R.string.metadata_nobio), "text/html", "utf-8");
		}
	}

	private class LoadSimilarTask extends AsyncTaskEx<Void, Void, ArrayList<ListEntry>> {

		@Override
		public void onPreExecute() {
			mSimilarList.setOnItemClickListener(null);
			mSimilarList.setAdapter(new NotificationAdapter(Metadata.this, NotificationAdapter.LOAD_MODE, getString(R.string.common_loading)));
		}

		@Override
		public ArrayList<ListEntry> doInBackground(Void... params) {

			try {
				Artist[] similar = mServer.getSimilarArtists(mArtistName, null);
				if (similar.length == 0)
					return null;

				ArrayList<ListEntry> iconifiedEntries = new ArrayList<ListEntry>();
				for (int i = 0; i < ((similar.length < 10) ? similar.length : 10); i++) {
					ListEntry entry = new ListEntry(similar[i], R.drawable.artist_icon, similar[i].getName(), similar[i].getURLforImageSize("extralarge"));
					iconifiedEntries.add(entry);
				}
				return iconifiedEntries;
			} catch (OutOfMemoryError e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			} catch (WSError e) {
			}
			return null;
		}

		@Override
		public void onPostExecute(ArrayList<ListEntry> iconifiedEntries) {
			if (iconifiedEntries != null) {
				mSimilarAdapter = new ListAdapter(Metadata.this, getImageCache());
				mSimilarAdapter.setSourceIconified(iconifiedEntries);
				mSimilarList.setAdapter(mSimilarAdapter);
				mSimilarList.setOnItemClickListener(new OnItemClickListener() {

					public void onItemClick(AdapterView<?> l, View v, int position, long id) {
						Artist artist = (Artist) mSimilarAdapter.getItem(position);
						Intent i = new Intent(Metadata.this, Metadata.class);
						i.putExtra("artist", artist.getName());
						startActivity(i);
					}

				});
			} else {
				mSimilarList.setAdapter(new NotificationAdapter(Metadata.this, NotificationAdapter.INFO_MODE, getString(R.string.metadata_nosimilar)));
			}
		}
	}

	private class LoadListenersTask extends AsyncTaskEx<Void, Void, ArrayList<ListEntry>> {

		@Override
		public void onPreExecute() {
			mFanList.setAdapter(new NotificationAdapter(Metadata.this, NotificationAdapter.LOAD_MODE, getString(R.string.common_loading)));
			mFanList.setOnItemClickListener(null);
		}

		@Override
		public ArrayList<ListEntry> doInBackground(Void... params) {
			try {
				User[] fans;
				if(mTrackName != null)
					fans = mServer.getTrackTopFans(mTrackName, mArtistName, null);
				else
					fans = mServer.getArtistTopFans(mArtistName, null);

				if (fans.length == 0)
					return null;
				ArrayList<ListEntry> iconifiedEntries = new ArrayList<ListEntry>();
				for (int i = 0; i < ((fans.length < 10) ? fans.length : 10); i++) {
					ListEntry entry = new ListEntry(fans[i], R.drawable.profile_unknown, fans[i].getName(), fans[i].getURLforImageSize("extralarge"),
							R.drawable.list_icon_arrow);
					iconifiedEntries.add(entry);
				}
				return iconifiedEntries;
			} catch (OutOfMemoryError e) {
				e.printStackTrace();
			} catch (Exception e) {
				e.printStackTrace();
			} catch (WSError e) {
			}
			return null;
		}

		@Override
		public void onPostExecute(ArrayList<ListEntry> iconifiedEntries) {
			if (iconifiedEntries != null) {
				mFanAdapter = new ListAdapter(Metadata.this, getImageCache());
				mFanAdapter.setSourceIconified(iconifiedEntries);
				mFanList.setAdapter(mFanAdapter);
				mFanList.setOnItemClickListener(new OnItemClickListener() {
					public void onItemClick(AdapterView<?> l, View v, int position, long id) {
						User user = (User) mFanAdapter.getItem(position);
						Intent profileIntent = new Intent(Metadata.this, fm.last.android.activity.Profile.class);
						profileIntent.putExtra("lastfm.profile.username", user.getName());
						startActivity(profileIntent);
					}
				});
			} else {
				mFanList.setAdapter(new NotificationAdapter(Metadata.this, NotificationAdapter.INFO_MODE, getString(R.string.metadata_nofans)));
			}
		}
	}

	/*
	 * 			

	 */
	
	private class ShowEventTask extends AsyncTaskEx<Void, Void, Intent> {
		Event event;
		
		public ShowEventTask(Event e) {
			event = e;
		}
		
		@Override
		public void onPreExecute() {
			mTagList.setAdapter(new NotificationAdapter(Metadata.this, NotificationAdapter.LOAD_MODE, getString(R.string.common_loading)));
			mTagList.setOnItemClickListener(null);
		}

		@Override
		public Intent doInBackground(Void... params) {
			Intent intent = fm.last.android.activity.Event.intentFromEvent(Metadata.this, event);
			try {
				Event[] events = mServer.getUserEvents((LastFMApplication.getInstance().session).getName());
				for (Event e : events) {
					if (e.getId() == event.getId()) {
						intent.putExtra("lastfm.event.status", e.getStatus());
						break;
					}

				}
			} catch (WSError e1) {
				// TODO Auto-generated catch block
				e1.printStackTrace();
			} catch (IOException e1) {
				// TODO Auto-generated catch block
				e1.printStackTrace();
			}
			return intent;
		}

		@Override
		public void onPostExecute(Intent intent) {
			mOnEventActivityResult = new EventActivityResult() {
				public void onEventStatus(int status) {
					event.setStatus(String.valueOf(status));
					mOnEventActivityResult = null;
				}
			};

			startActivityForResult(intent, 0);
		}
	}

	
	private class LoadTagsTask extends AsyncTaskEx<Void, Void, ArrayList<ListEntry>> {

		@Override
		public void onPreExecute() {
			mTagList.setAdapter(new NotificationAdapter(Metadata.this, NotificationAdapter.LOAD_MODE, getString(R.string.common_loading)));
			mTagList.setOnItemClickListener(null);
		}

		@Override
		public ArrayList<ListEntry> doInBackground(Void... params) {
			try {
				Tag[] tags;
				if(mTrackName != null)
					tags = mServer.getTrackTopTags(mArtistName, mTrackName, null);
				else
					tags = mServer.getArtistTopTags(mArtistName, null);
				if (tags.length == 0)
					return null;
				ArrayList<ListEntry> iconifiedEntries = new ArrayList<ListEntry>();
				for (int i = 0; i < ((tags.length < 10) ? tags.length : 10); i++) {
					ListEntry entry = new ListEntry(tags[i], R.drawable.list_icon_station, tags[i].getName());
					entry.centerIcon = true;
					iconifiedEntries.add(entry);
				}
				return iconifiedEntries;
			} catch (OutOfMemoryError e) {
				e.printStackTrace();
			} catch (Exception e) {
				e.printStackTrace();
			} catch (WSError e) {
			}
			return null;
		}

		@Override
		public void onPostExecute(ArrayList<ListEntry> iconifiedEntries) {
			if (iconifiedEntries != null) {
				mTagAdapter = new ListAdapter(Metadata.this, getImageCache());
				mTagAdapter.setSourceIconified(iconifiedEntries);
				mTagList.setAdapter(mTagAdapter);
				mTagList.setOnItemClickListener(new OnItemClickListener() {

					public void onItemClick(AdapterView<?> l, View v, int position, long id) {
						Tag tag = (Tag) mTagAdapter.getItem(position);
						mTagAdapter.enableLoadBar(position);
						LastFMApplication.getInstance().playRadioStation(Metadata.this, "lastfm://globaltags/" + Uri.encode(tag.getName()), true);
					}

				});
			} else {
				mTagList.setAdapter(new NotificationAdapter(Metadata.this, NotificationAdapter.INFO_MODE, getString(R.string.metadata_notags)));
			}
		}
	}

	/**
	 * This load task is slightly bigger as it has to handle OnTour indicator
	 * and Metadata's event list. The main problem here is new events must be
	 * downloaded on track change even if the user is viewing old events in the
	 * metadata view.
	 * 
	 * @author Lukasz Wisniewski
	 */
	private class LoadEventsTask extends AsyncTaskEx<Void, Void, Boolean> {

		/**
		 * New adapter representing events data
		 */
		private BaseAdapter mNewEventAdapter;

		@Override
		public void onPreExecute() {
			mEventList.setOnItemClickListener(null);
			mEventList.setAdapter(new NotificationAdapter(Metadata.this, NotificationAdapter.LOAD_MODE, getString(R.string.common_loading)));
			mOntourButton.setVisibility(View.GONE);
			mOntourButton.invalidate();
		}

		@Override
		public Boolean doInBackground(Void... params) {
			boolean result = false;

			mNewEventAdapter = new EventListAdapter(Metadata.this);

			try {
				Event[] events = mServer.getArtistEvents(mArtistName);
				((EventListAdapter) mNewEventAdapter).setEventsSource(events);
				if (events.length > 0)
					result = true;
			} catch (OutOfMemoryError e) {
				e.printStackTrace();

			} catch (IOException e) {
				e.printStackTrace();
			} catch (WSError e) {
			}

			if (!result) {
				mNewEventAdapter = new NotificationAdapter(Metadata.this, NotificationAdapter.INFO_MODE, getString(R.string.metadata_noevents));
				mEventList.setOnItemClickListener(null);
			}

			return result;
		}

		@Override
		public void onPostExecute(Boolean result) {
			mEventAdapter = mNewEventAdapter;
			mEventList.setAdapter(mEventAdapter);
			if (result) {
				mEventList.setOnItemClickListener(mEventOnItemClickListener);
				mOntourButton.setVisibility(View.VISIBLE);
			}
		}
	}

	private OnItemClickListener mEventOnItemClickListener = new OnItemClickListener() {

		public void onItemClick(final AdapterView<?> parent, final View v, final int position, long id) {
			final Event event = (Event) parent.getAdapter().getItem(position);
			new ShowEventTask(event).execute((Void)null);
		}

	};

	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		if (requestCode == 0 && resultCode == RESULT_OK) {
			int status = data.getExtras().getInt("status", -1);
			if (mOnEventActivityResult != null && status != -1) {
				mOnEventActivityResult.onEventStatus(status);
			}
		}
	}

}
