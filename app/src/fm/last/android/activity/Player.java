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
import java.util.Formatter;
import java.util.concurrent.RejectedExecutionException;

import android.app.Activity;
import android.app.ProgressDialog;
import android.app.SearchManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.net.Uri;
import fm.last.android.utils.AsyncTaskEx;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.Animation.AnimationListener;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import fm.last.android.Amazon;
import fm.last.android.AndroidLastFmServerFactory;
import fm.last.android.LastFMApplication;
import fm.last.android.R;
import fm.last.android.player.IRadioPlayer;
import fm.last.android.player.RadioPlayerService;
import fm.last.android.widget.AdArea;
import fm.last.api.Event;
import fm.last.api.LastFmServer;
import fm.last.api.Station;
import fm.last.api.WSError;

public class Player extends Activity {

	private ImageButton mLoveButton;
	private ImageButton mBanButton;
	private ImageButton mStopButton;
	private ImageButton mNextButton;
	private ImageButton mOntourButton;
	private ImageView mAlbum;
	private TextView mCurrentTime;
	private TextView mTotalTime;
	private TextView mArtistName;
	private TextView mTrackName;
	private TextView mTrackContext;
	private ProgressBar mProgress;
	private long mDuration;
	private boolean paused;
	private boolean loved = false;

	private ProgressDialog mTuningDialog;

	private String mCachedArtist = null;
	private String mCachedTrack = null;

	private static final int REFRESH = 1;

	private boolean tuning = false;
	
	private PowerManager.WakeLock wakelock = null;
	
	LastFmServer mServer = AndroidLastFmServerFactory.getServer();

	private IntentFilter mIntentFilter;

	@Override
	public void onCreate(Bundle icicle) {
		super.onCreate(icicle);

		setContentView(R.layout.audio_player);
		setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);

		mCurrentTime = (TextView) findViewById(R.id.currenttime);
		mTotalTime = (TextView) findViewById(R.id.totaltime);
		mProgress = (ProgressBar) findViewById(android.R.id.progress);
		mProgress.setMax(1000);
		mAlbum = (ImageView) findViewById(R.id.album);
		LayoutParams params = mAlbum.getLayoutParams();
		if (AdArea.adsEnabled(this)) {
			params.width -= 54;
			params.height -= 54;
		}
		mAlbum.setLayoutParams(params);
		mArtistName = (TextView) findViewById(R.id.track_artist);
		mTrackName = (TextView) findViewById(R.id.track_title);
		mTrackContext = (TextView) findViewById(R.id.track_context);
		mTrackContext.setVisibility(View.GONE);

		mLoveButton = (ImageButton) findViewById(R.id.love);
		mLoveButton.setOnClickListener(mLoveListener);
		mBanButton = (ImageButton) findViewById(R.id.ban);
		mBanButton.setOnClickListener(mBanListener);
		mStopButton = (ImageButton) findViewById(R.id.stop);
		mStopButton.requestFocus();
		mStopButton.setOnClickListener(mStopListener);
		mNextButton = (ImageButton) findViewById(R.id.skip);
		mNextButton.setOnClickListener(mNextListener);
		mOntourButton = (ImageButton) findViewById(R.id.ontour);
		mOntourButton.setOnClickListener(mOntourListener);

		LastFMApplication.getInstance().bindService(
				new Intent(LastFMApplication.getInstance(),
						fm.last.android.player.RadioPlayerService.class),
				new ServiceConnection() {
					public void onServiceConnected(ComponentName comp,
							IBinder binder) {
						IRadioPlayer player = IRadioPlayer.Stub
								.asInterface(binder);
						try {
							String url = player.getStationUrl();
							
							if(url != null &&
									(url.startsWith("lastfm://playlist/") || url.startsWith("lastfm://usertags/") || url.endsWith("/loved"))) {
								findViewById(R.id.noticeContainer).setVisibility(View.VISIBLE);
								TextView notice = (TextView) findViewById(R.id.notice);
								notice.setSelected(true);
								notice.setOnClickListener(new View.OnClickListener() {
	
									public void onClick(View v) {
										Intent i = new Intent(Intent.ACTION_VIEW);
										i.setData(Uri.parse("http://www.last.fm/stationchanges2010"));
										startActivity(i);
									}
									
								});
							}
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
				}, Context.BIND_AUTO_CREATE);
		
		ImageButton dismiss = (ImageButton) findViewById(R.id.dismiss);
		dismiss.setOnClickListener(new View.OnClickListener() {

			public void onClick(View v) {
				findViewById(R.id.noticeContainer).setVisibility(View.GONE);
			}
			
		});
		
		mIntentFilter = new IntentFilter();
		mIntentFilter.addAction(RadioPlayerService.META_CHANGED);
		mIntentFilter.addAction(RadioPlayerService.PLAYBACK_FINISHED);
		mIntentFilter.addAction(RadioPlayerService.PLAYBACK_STATE_CHANGED);
		mIntentFilter.addAction(RadioPlayerService.STATION_CHANGED);
		mIntentFilter.addAction(RadioPlayerService.PLAYBACK_ERROR);
		mIntentFilter.addAction(RadioPlayerService.ARTWORK_AVAILABLE);
		mIntentFilter.addAction("fm.last.android.ERROR");

		Intent intent = getIntent();
		if (intent != null) {
			if(intent.getAction() != null && intent.getAction().equals("android.media.action.MEDIA_PLAY_FROM_SEARCH")) {
				new SearchStationTask().execute((Void)null);
				tuning = true;
			} else if(intent.getData() != null && intent.getData().getScheme() != null && intent.getData().getScheme().equals("lastfm")) {
				LastFMApplication.getInstance().playRadioStation(Player.this, intent.getData().toString(), false);
				tuning = true;
			}
		}
		if (icicle != null) {
			mCachedArtist = icicle.getString("artist");
			mCachedTrack = icicle.getString("track");
			if (icicle.getBoolean("isOnTour", false))
				mOntourButton.setVisibility(View.VISIBLE);
			loved = icicle.getBoolean("loved", false);
			if (loved) {
				mLoveButton.setImageResource(R.drawable.loved);
			} else {
				mLoveButton.setImageResource(R.drawable.love);
			}
		}
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		menu.findItem(R.id.buy_menu_item).setEnabled(
				Amazon.getAmazonVersion(this) > 0);

		return super.onPrepareOptionsMenu(menu);
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.player, menu);
		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == R.id.info_menu_item) {
			showMetadataIntent();
			return true;
		}

		if (handleOptionItemSelected(this, item))
			return true;

		return super.onOptionsItemSelected(item);
	}

	public boolean handleOptionItemSelected(Context c, MenuItem item) {
		switch (item.getItemId()) {
		case R.id.buy_menu_item:
			try {
				LastFMApplication.getInstance().tracker.trackEvent("Clicks", // Category
						"player-buy", // Action
						"", // Label
						0); // Value
			} catch (Exception e) {
				//Google Analytics doesn't appear to be thread safe
			}
			Amazon.searchForTrack(this, mArtistName.getText().toString(),
					mTrackName.getText().toString());
			break;
		case R.id.share_menu_item:
			try {
				if (LastFMApplication.getInstance().player == null)
					return false;
				Intent intent = new Intent(c, ShareResolverActivity.class);
				intent.putExtra(Share.INTENT_EXTRA_ARTIST, LastFMApplication
						.getInstance().player.getArtistName());
				intent.putExtra(Share.INTENT_EXTRA_TRACK, LastFMApplication
						.getInstance().player.getTrackName());
				c.startActivity(intent);
			} catch (Exception e) {
				e.printStackTrace();
			}
			break;
		case R.id.tag_menu_item:
			fireTagActivity(c);
			break;

		default:
			break;
		}
		return false;
	}

	private static void fireTagActivity(Context c) {
		String artist = null;
		String track = null;

		try {
			if (LastFMApplication.getInstance().player == null)
				return;
			artist = LastFMApplication.getInstance().player.getArtistName();
			track = LastFMApplication.getInstance().player.getTrackName();
			Intent myIntent = new Intent(c, fm.last.android.activity.Tag.class);
			myIntent.putExtra("lastfm.artist", artist);
			myIntent.putExtra("lastfm.track", track);
			c.startActivity(myIntent);
		} catch (RemoteException e) {
			e.printStackTrace();
		}
	}

	@Override
	public void onStart() {
		super.onStart();
		paused = false;
	}

	@Override
	public void onStop() {

		paused = true;
		mHandler.removeMessages(REFRESH);

		super.onStop();
	}

	@Override
	public void onSaveInstanceState(Bundle outState) {
		outState.putBoolean("configchange", getChangingConfigurations() != 0);
		outState.putString("artist", mArtistName.getText().toString());
		outState.putString("track", mTrackName.getText().toString());
		outState.putBoolean("isOnTour",
				mOntourButton.getVisibility() == View.VISIBLE);
		outState.putBoolean("loved",
				loved);
		super.onSaveInstanceState(outState);
	}

	@Override
	protected void onPause() {
		try {
			unregisterReceiver(mStatusListener);
		} catch(IllegalArgumentException e) {
			//The listener wasn't registered yet
		}
		mHandler.removeMessages(REFRESH);
		if (LastFMApplication.getInstance().player != null)
			LastFMApplication.getInstance().unbindPlayerService();
		if(wakelock != null && wakelock.isHeld())
			wakelock.release();
		super.onPause();
	}

	@Override
	public void onResume() {
		super.onResume();

		if(PreferenceManager.getDefaultSharedPreferences(LastFMApplication.getInstance()).getBoolean("screen_wakelock", false)) {
			PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
			wakelock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "Last.fm");
			wakelock.acquire();
		}

		registerReceiver(mStatusListener, mIntentFilter);
		if (LastFMApplication.getInstance().player == null)
			LastFMApplication.getInstance().bindPlayerService();
		updateTrackInfo();

		try {
			new RefreshTask().execute((Void)null);
		} catch (RejectedExecutionException e) {
			queueNextRefresh(500);
		}
		
		try {
			LastFMApplication.getInstance().tracker.trackPageView("/Player");
		} catch (Exception e) {
			//Google Analytics doesn't appear to be thread safe
		}

		if(!tuning) {
			bindService(new Intent(Player.this,
					fm.last.android.player.RadioPlayerService.class),
					new ServiceConnection() {
						public void onServiceConnected(ComponentName comp,
								IBinder binder) {
							IRadioPlayer player = IRadioPlayer.Stub
									.asInterface(binder);
							try {
								if (player.getState() == RadioPlayerService.STATE_STOPPED) {
									Intent i = new Intent(Player.this, Profile.class);
									startActivity(i);
									finish();
								}
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
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
	}

	private View.OnClickListener mLoveListener = new View.OnClickListener() {

		public void onClick(View v) {
			Intent i = new Intent("fm.last.android.LOVE");
			sendBroadcast(i);
			bindService(new Intent(Player.this,
					fm.last.android.player.RadioPlayerService.class),
					new ServiceConnection() {
						public void onServiceConnected(ComponentName comp,
								IBinder binder) {
							IRadioPlayer player = IRadioPlayer.Stub
									.asInterface(binder);
							try {
								if (player.isPlaying())
									player.setLoved(true);
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
			mLoveButton.setImageResource(R.drawable.loved);
			loved = true;
			
			try {
				LastFMApplication.getInstance().tracker.trackEvent("Clicks", // Category
						"player-love", // Action
						"", // Label
						0); // Value
			} catch (Exception e) {
				//Google Analytics doesn't appear to be thread safe
			}
		}
	};

	private View.OnClickListener mBanListener = new View.OnClickListener() {

		public void onClick(View v) {
			Intent i = new Intent("fm.last.android.BAN");
			sendBroadcast(i);
			try {
				LastFMApplication.getInstance().tracker.trackEvent("Clicks", // Category
						"player-ban", // Action
						"", // Label
						0); // Value
			} catch (Exception e) {
				//Google Analytics doesn't appear to be thread safe
			}
			bindService(new Intent(Player.this,
					fm.last.android.player.RadioPlayerService.class),
					new ServiceConnection() {
						public void onServiceConnected(ComponentName comp,
								IBinder binder) {
							IRadioPlayer player = IRadioPlayer.Stub
									.asInterface(binder);
							try {
								if (player.isPlaying())
									player.skip();
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
	};

	private View.OnClickListener mNextListener = new View.OnClickListener() {

		public void onClick(View v) {
			try {
				LastFMApplication.getInstance().tracker.trackEvent("Clicks", // Category
						"player-skip", // Action
						"", // Label
						0); // Value
			} catch (Exception e) {
				//Google Analytics doesn't appear to be thread safe
			}
			bindService(new Intent(Player.this,
					fm.last.android.player.RadioPlayerService.class),
					new ServiceConnection() {
						public void onServiceConnected(ComponentName comp,
								IBinder binder) {
							IRadioPlayer player = IRadioPlayer.Stub
									.asInterface(binder);
							try {
								if (player.isPlaying() || player.getState() == RadioPlayerService.STATE_PAUSED)
									player.skip();
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
	};

	private void showMetadataIntent() {
		showMetadataIntent(false);
	}

	private void showEventsMetadataIntent() {
		showMetadataIntent(true);
	}

	private void showMetadataIntent(boolean gotoEventsTab) {
		Intent metaIntent = new Intent(this,
				fm.last.android.activity.Metadata.class);
		metaIntent.putExtra("artist", mArtistName.getText());
		metaIntent.putExtra("track", mTrackName.getText());
		if (gotoEventsTab)
			metaIntent.putExtra("show_events", true);

		startActivity(metaIntent);
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
			showEventsMetadataIntent();
		}

	};

	private View.OnClickListener mStopListener = new View.OnClickListener() {

		public void onClick(View v) {
			try {
				LastFMApplication.getInstance().tracker.trackEvent("Clicks", // Category
						"player-stop", // Action
						"", // Label
						0); // Value
			} catch (Exception e) {
				//Google Analytics doesn't appear to be thread safe
			}

			bindService(new Intent(Player.this,
					fm.last.android.player.RadioPlayerService.class),
					new ServiceConnection() {
						public void onServiceConnected(ComponentName comp,
								IBinder binder) {
							IRadioPlayer player = IRadioPlayer.Stub
									.asInterface(binder);
							try {
								if (player.getState() == RadioPlayerService.STATE_PAUSED)
									LastFMApplication.getInstance().playRadioStation(Player.this, player.getStationUrl(), false);
								else if (player.getState() != RadioPlayerService.STATE_STOPPED)
									player.pause();
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
	};

	private BroadcastReceiver mStatusListener = new BroadcastReceiver() {

		@Override
		public void onReceive(Context context, Intent intent) {

			String action = intent.getAction();
			if (action.equals(RadioPlayerService.META_CHANGED) || action.equals(RadioPlayerService.ARTWORK_AVAILABLE)) {
				// redraw the artist/title info and
				// set new max for progress bar
				updateTrackInfo();
			} else if (action.equals(RadioPlayerService.PLAYBACK_FINISHED)) {
				finish();
			} else if (action.equals(RadioPlayerService.STATION_CHANGED)) {
				// FIXME: this *should* be handled by the metadata activity now
				// if(mDetailFlipper.getDisplayedChild() == 1)
				// mDetailFlipper.showPrevious();
			} else if (action.equals(RadioPlayerService.PLAYBACK_ERROR) || action.equals("fm.last.android.ERROR")) {
				// TODO add a skip counter and try to skip 3 times before
				// display an error message
				if (mTuningDialog != null) {
					mTuningDialog.dismiss();
					mTuningDialog = null;
				}
				WSError error = intent.getParcelableExtra("error");
				if (error != null) {
					LastFMApplication.getInstance().presentError(Player.this,
							error);
				} else {
					LastFMApplication.getInstance().presentError(
							Player.this,
							getResources().getString(
									R.string.ERROR_PLAYBACK_FAILED_TITLE),
							getResources().getString(
									R.string.ERROR_PLAYBACK_FAILED));
				}
			}
		}
	};

	private void updateTrackInfo() {
		LastFMApplication.getInstance().bindService(
				new Intent(LastFMApplication.getInstance(),
						fm.last.android.player.RadioPlayerService.class),
				new ServiceConnection() {
					public void onServiceConnected(ComponentName comp,
							IBinder binder) {
						IRadioPlayer player = IRadioPlayer.Stub
								.asInterface(binder);
						try {
							String artistName = player.getArtistName();
							String trackName = player.getTrackName();
							String[] trackContext = player.getContext();
							String stationURL = player.getStationUrl();
							loved = player.getLoved();
							try {
								if(player.getArtwork() != null)
									mAlbum.setImageBitmap(player.getArtwork());
								else
									mAlbum.setImageResource(R.drawable.no_artwork);
							} catch (java.lang.OutOfMemoryError e) {
								mAlbum.setImageResource(R.drawable.no_artwork);
							}
							if (loved) {
								mLoveButton.setImageResource(R.drawable.loved);
							} else {
								mLoveButton.setImageResource(R.drawable.love);
							}

							if ((mArtistName != null && mArtistName.getText() != null && mTrackName != null && mTrackName.getText() != null) && (!mArtistName.getText().equals(artistName)
									|| !mTrackName.getText().equals(trackName))) {
								if (artistName == null || artistName
										.equals(RadioPlayerService.UNKNOWN)) {
									mArtistName.setText("");
								} else {
									mArtistName.setText(artistName);
								}
								if (trackName == null || trackName
										.equals(RadioPlayerService.UNKNOWN)) {
									mTrackName.setText("");
								} else {
									mTrackName.setText(trackName);
								}
								if (trackContext == null || trackContext.length == 0 || trackContext[0] == null || stationURL == null) {
									mTrackContext.setVisibility(View.GONE);
									mTrackContext.setText("");
								} else {
									String context = "";
									if(stationURL.endsWith("/friends") || stationURL.endsWith("/neighbours") || stationURL.contains("/friends/") || stationURL.contains("/neighbours/"))
										context += "From ";
									else
										context += "Similar to ";
									
									context += trackContext[0];
									if(stationURL.endsWith("/friends") || stationURL.endsWith("/neighbours") || stationURL.contains("/friends/") || stationURL.contains("/neighbours/"))
										if(context.endsWith("s"))
											context += "'";
										else
											context += "'s";

									if(trackContext.length > 1) {
										context += " and " + trackContext[1];
										if(stationURL.endsWith("/friends") || stationURL.endsWith("/neighbours") || stationURL.contains("/friends/") || stationURL.contains("/neighbours/"))
											if(context.endsWith("s"))
												context += "'";
											else
												context += "'s";
									}
									if(stationURL.endsWith("/friends") || stationURL.endsWith("/neighbours") || stationURL.contains("/friends/") || stationURL.contains("/neighbours/"))
										if(trackContext.length > 1)
											context += " libraries";
										else
											context += " library";
									
									mTrackContext.setVisibility(View.VISIBLE);
									mTrackContext.setText(context);
								}

								if (mTuningDialog != null
										&& player.getState() == RadioPlayerService.STATE_TUNING) {
									mTuningDialog = ProgressDialog.show(
											Player.this, "",
											getString(R.string.player_tuning),
											true, false);
									mTuningDialog
											.setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
									mTuningDialog.setCancelable(true);
								}

								if (!(mCachedArtist != null
										&& mCachedArtist.equals(artistName)
										&& mCachedTrack != null
										&& mCachedTrack.equals(trackName))) {
									new LoadEventsTask().execute((Void) null);
								}
							}
						} catch (java.util.concurrent.RejectedExecutionException e) {
							e.printStackTrace();
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
				}, Context.BIND_AUTO_CREATE);
	}

	private void queueNextRefresh(long delay) {
		if (!paused) {
			Message msg = mHandler.obtainMessage(REFRESH);
			mHandler.removeMessages(REFRESH);
			mHandler.sendMessageDelayed(msg, delay);
		}
	}

	private long refreshNow() {
		LastFMApplication.getInstance().bindService(
				new Intent(LastFMApplication.getInstance(),
						fm.last.android.player.RadioPlayerService.class),
				new ServiceConnection() {
					public void onServiceConnected(ComponentName comp,
							IBinder binder) {
						IRadioPlayer player = IRadioPlayer.Stub
								.asInterface(binder);
						try {
							if(player.getState() == RadioPlayerService.STATE_PAUSED) {
								mStopButton.setImageResource(R.drawable.play);
							} else {
								mStopButton.setImageResource(R.drawable.pause);
							}
							mDuration = player.getDuration();
							long pos = player.getPosition();
							if ((pos >= 0) && (mDuration > 0)
									&& (pos <= mDuration)) {
								mCurrentTime.setText(makeTimeString(
										Player.this, pos / 1000));
								mTotalTime.setText(makeTimeString(Player.this,
										mDuration / 1000));
								mProgress
										.setProgress((int) (1000 * pos / mDuration));
								mProgress.setSecondaryProgress(player.getBufferPercent() * 10);
								if (mTuningDialog != null) {
									mTuningDialog.dismiss();
									mTuningDialog = null;
								}
							} else {
								mCurrentTime.setText("--:--");
								mTotalTime.setText("--:--");
								mProgress.setProgress(0);
								mProgress.setSecondaryProgress(player.getBufferPercent() * 10);
								if (player.isPlaying() && mTuningDialog != null) {
									mTuningDialog.dismiss();
									mTuningDialog = null;
								}
							}
							// return the number of milliseconds until the next
							// full second, so
							// the counter can be updated at just the right time
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
				}, Context.BIND_AUTO_CREATE);

		return 500;
	}

	private class RefreshTask extends AsyncTaskEx<Void, Void, Void> {
		@Override
		protected Void doInBackground(Void... params) {
			long next = refreshNow();
			queueNextRefresh(next);
			return null;
		}
		
	}
	
	private final Handler mHandler = new Handler() {

		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
			case REFRESH:
				try {
					new RefreshTask().execute((Void)null);
				} catch (RejectedExecutionException e) {
					queueNextRefresh(500);
				}
				break;
			default:
				break;
			}
		}
	};

	/*
	 * Try to use String.format() as little as possible, because it creates a
	 * new Formatter every time you call it, which is very inefficient. Reusing
	 * an existing Formatter more than tripled the speed of makeTimeString().
	 * This Formatter/StringBuilder are also used by makeAlbumSongsLabel()
	 * 
	 * Hi I changed this due to a bug I managed to make at time zero. But
	 * honestly, this kind of optimisation is a bit much. --mxcl
	 */

	public static String makeTimeString(Context context, long secs) {
		return new Formatter().format("%02d:%02d", secs / 60, secs % 60)
				.toString();
	}

	private class SearchStationTask extends AsyncTaskEx<Void, Void, Station> {

		@Override
		protected Station doInBackground(Void... arg0) {
			String query = Player.this.getIntent().getStringExtra(SearchManager.QUERY);
			if(LastFMApplication.getInstance().session != null) {
				String username = LastFMApplication.getInstance().session.getName();
				
				try {
					if(query.equals("my library")) {
						return new Station("", "", "lastfm://user/" + Uri.encode(username) + "/personal", "");
					} else if(query.equals("my recommendations")) {
						return new Station("", "", "lastfm://user/" + Uri.encode(username) + "/recommended", "");
					} else if(query.equals("my loved tracks")) {
						return new Station("", "", "lastfm://user/" + Uri.encode(username) + "/loved", "");
					} else if(query.equals("my neighborhood") || query.equals("my neighbourhood")) {
						return new Station("", "", "lastfm://user/" + Uri.encode(username) + "/neighbours", "");
					} else {
						Station s = mServer.searchForStation(query);
						return s;
					}
				} catch (NullPointerException e) {
				} catch (IOException e) {
				}
			}
			return null;
		}
		
		@Override
		public void onPostExecute(Station result) {
			String query = Player.this.getIntent().getStringExtra(SearchManager.QUERY);

			if(result != null) {
				LastFMApplication.getInstance().playRadioStation(Player.this, result.getUrl(), false);
			} else {
				Intent i = new Intent(Player.this, Profile.class);
				i.putExtra(SearchManager.QUERY, query);
				startActivity(i);
				finish();
			}
		}
	}
	
	private class LoadEventsTask extends AsyncTaskEx<Void, Void, Boolean> {
		String mArtist = null;

		@Override
		public void onPreExecute() {
			mArtist = mArtistName.getText().toString();
			mOntourButton.clearAnimation();
			mOntourButton.setVisibility(View.GONE);
			mOntourButton.invalidate();
		}

		@Override
		public Boolean doInBackground(Void... params) {
			boolean result = false;
			if (mArtist != null
					&& (mArtist.equals(RadioPlayerService.UNKNOWN) || Player.this.mArtistName.getText().toString()
							.compareToIgnoreCase(mArtist) != 0))
				return false;

			try {
				Event[] events = mServer.getArtistEvents(mArtist);
				if (events.length > 0)
					result = true;

			} catch (Exception e) {
				e.printStackTrace();
			} catch (WSError e) {
			}
			return result;
		}

		@Override
		public void onPostExecute(Boolean result) {

			// Check if this is a stale event request
			if (Player.this.mArtistName.getText().toString()
					.compareToIgnoreCase(mArtist) != 0)
				return;

			if (result) {

				Animation a = AnimationUtils.loadAnimation(Player.this,
						R.anim.tag_fadein);
				a.setAnimationListener(new AnimationListener() {

					public void onAnimationEnd(Animation animation) {
					}

					public void onAnimationRepeat(Animation animation) {
					}

					public void onAnimationStart(Animation animation) {
						mOntourButton.setVisibility(View.VISIBLE);
					}

				});
				mOntourButton.startAnimation(a);
			} else {

			}
		}
	}

}
