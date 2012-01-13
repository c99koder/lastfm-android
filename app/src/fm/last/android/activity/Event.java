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

import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.HashMap;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.RadioGroup;
import android.widget.TextView;
import fm.last.android.AndroidLastFmServerFactory;
import fm.last.android.LastFMApplication;
import fm.last.android.R;
import fm.last.android.utils.AsyncTaskEx;
import fm.last.android.widget.AlbumArt;
import fm.last.api.ImageUrl;
import fm.last.api.LastFmServer;
import fm.last.api.WSError;

public class Event extends Activity {

	private TextView mTitle;
	private TextView mArtists;
	private TextView mVenue;
	private TextView mStreet;
	private TextView mMonth;
	private TextView mDay;
	private RadioGroup mAttendance;
	private AlbumArt mPosterImage;

	public interface EventActivityResult {
		public void onEventStatus(int status);
	}

	private static int resourceToStatus(int resId) {
		switch (resId) {
		case R.id.attending:
			return 0;
		case R.id.maybe:
			return 1;
		case R.id.notattending:
			return 2;
		}
		return 2;
	}

	private static int statusToResource(int status) {
		switch (status) {
		case 0:
			return R.id.attending;
		case 1:
			return R.id.maybe;
		case 2:
			return R.id.notattending;
		}
		return R.id.notattending;
	}

	@Override
	public void onCreate(Bundle icicle) {
		super.onCreate(icicle);
		setContentView(R.layout.event);

		mTitle = (TextView) findViewById(R.id.title);
		mTitle.setText(getIntent().getStringExtra("lastfm.event.title"));

		mArtists = (TextView) findViewById(R.id.artists);
		mArtists.setText(getIntent().getStringExtra("lastfm.event.artists"));

		mVenue = (TextView) findViewById(R.id.venue);
		mVenue.setText(getIntent().getStringExtra("lastfm.event.venue"));

		mStreet = (TextView) findViewById(R.id.street);
		mStreet.setText(getIntent().getStringExtra("lastfm.event.street"));

		mMonth = (TextView) findViewById(R.id.month);
		mMonth.setText(getIntent().getStringExtra("lastfm.event.month"));

		mDay = (TextView) findViewById(R.id.day);
		mDay.setText(getIntent().getStringExtra("lastfm.event.day"));

		mPosterImage = (AlbumArt) findViewById(R.id.poster);
		mPosterImage.fetch(getIntent().getStringExtra("lastfm.event.poster"));

		int statusResource;
		try {
			statusResource = statusToResource(Integer.parseInt(getIntent().getStringExtra("lastfm.event.status")));
		} catch (Exception e) {
			statusResource = R.id.notattending;
		}
		mAttendance = (RadioGroup) findViewById(R.id.attend);
		mAttendance.check(statusResource);

		findViewById(R.id.cancel).setOnClickListener(new OnClickListener() {
			public void onClick(View v) {
				setResult(RESULT_CANCELED);
				finish();
			}
		});

		@SuppressWarnings("unchecked")
		HashMap<String, String> ticketMap = (HashMap<String,String>)(getIntent().getSerializableExtra("lastfm.event.ticketurls"));
		if(ticketMap.size() > 0) {
			findViewById(R.id.buytickets).setOnClickListener(new OnClickListener() {
				public void onClick(View v) {
					final Intent myIntent = new Intent(Event.this, TicketProviderPopup.class);
					myIntent.putExtra("ticketurls", (Serializable)getIntent().getSerializableExtra("lastfm.event.ticketurls"));
					startActivity(myIntent);
				}
			});
		} else {
			findViewById(R.id.buytickets).setVisibility(View.GONE);
		}
		findViewById(R.id.showmap).setOnClickListener(new OnClickListener() {
			public void onClick(View v) {
				String query = "";
				String street = getIntent().getStringExtra("lastfm.event.street");
				String city = getIntent().getStringExtra("lastfm.event.city");
				String postalcode = getIntent().getStringExtra("lastfm.event.postalcode");
				String country = getIntent().getStringExtra("lastfm.event.country");

				if (street != null && street.length() > 0)
					query += street + ",";
				if (city != null && city.length() > 0)
					query += " " + city + ",";
				if (postalcode != null && postalcode.length() > 0)
					query += " " + postalcode;
				if (country != null && country.length() > 0)
					query += " " + country;
				final Intent myIntent = new Intent(android.content.Intent.ACTION_VIEW, Uri.parse("http://maps.google.com/?f=q&q=" + query
						+ "&ie=UTF8&om=1&iwloc=addr"));
				startActivity(myIntent);
				finish();
			}
		});

		findViewById(R.id.ok).setOnClickListener(new OnClickListener() {
			public void onClick(View v) {
				new SaveEventTask().execute((Void)null);
			}
		});
	}

	@Override
	public void onResume() {
		super.onResume();
		try {
			LastFMApplication.getInstance().tracker.trackPageView("/Event");
		} catch (Exception e) {
			//Google Analytics doesn't appear to be thread safe
		}
	}

	@Override
	protected void onStop() {
		mPosterImage.cancel();
		super.onStop();
	}

	public static Intent intentFromEvent(Context packageContext, fm.last.api.Event event) {
		Intent intent = new Intent(packageContext, fm.last.android.activity.Event.class);
		intent.putExtra("lastfm.event.id", Integer.toString(event.getId()));
		intent.putExtra("lastfm.event.title", event.getTitle());
		String artists = "";
		for (String artist : event.getArtists()) {
			if (artists.length() > 0)
				artists += ", ";
			artists += artist;
		}
		for (ImageUrl image : event.getImages()) {
			if (image.getSize().contentEquals("large"))
				intent.putExtra("lastfm.event.poster", image.getUrl());
		}
		intent.putExtra("lastfm.event.artists", artists);
		intent.putExtra("lastfm.event.venue", event.getVenue().getName());
		intent.putExtra("lastfm.event.street", event.getVenue().getLocation().getStreet());
		intent.putExtra("lastfm.event.city", event.getVenue().getLocation().getCity());
		intent.putExtra("lastfm.event.postalcode", event.getVenue().getLocation().getPostalcode());
		intent.putExtra("lastfm.event.country", event.getVenue().getLocation().getCountry());
		intent.putExtra("lastfm.event.month", new SimpleDateFormat("MMM").format(event.getStartDate()));
		intent.putExtra("lastfm.event.day", new SimpleDateFormat("d").format(event.getStartDate()));
		intent.putExtra("lastfm.event.status", event.getStatus());
		intent.putExtra("lastfm.event.ticketurls", (Serializable)event.getTicketUrls());
		return intent;
	}
	
	private class SaveEventTask extends AsyncTaskEx<Void, Void, Boolean> {
		ProgressDialog mLoadDialog = null;
		WSError mError = null;

		@Override
		public void onPreExecute() {
			if (mLoadDialog == null) {
				mLoadDialog = ProgressDialog.show(Event.this, "", getString(R.string.event_saving), true, false);
				mLoadDialog.setCancelable(true);
			}
		}

		@Override
		public Boolean doInBackground(Void... params) {
			LastFmServer server = AndroidLastFmServerFactory.getServer();

			try {
				int status = resourceToStatus(mAttendance.getCheckedRadioButtonId());
				server.attendEvent(getIntent().getStringExtra("lastfm.event.id"), String.valueOf(status), (LastFMApplication.getInstance().session)
						.getKey());
				setResult(RESULT_OK, new Intent().putExtra("status", status));
				return true;
			} catch (WSError e) {
				mError = e;
			} catch (Exception e) {
				e.printStackTrace();
			}
			return false;
		}

		@Override
		public void onPostExecute(Boolean result) {
			try {
				if (mLoadDialog != null) {
					mLoadDialog.dismiss();
					mLoadDialog = null;
				}
			} catch (IllegalArgumentException e) {
				e.printStackTrace();
			}
			if(result) {
				finish();
			} else {
				if(mError != null)
					LastFMApplication.getInstance().presentError(Event.this, mError);
			}
		}
	}
}
