/*
 * FlightIntel for Pilots
 *
 * Copyright 2011 Nadeem Hasan <nhasan@nadmm.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>. 
 */

package com.nadmm.airports;

import java.util.Arrays;

import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQueryBuilder;
import android.graphics.Color;
import android.location.Location;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.TableLayout.LayoutParams;

import com.nadmm.airports.DatabaseManager.Airports;
import com.nadmm.airports.DatabaseManager.Com;
import com.nadmm.airports.DatabaseManager.Nav1;

public class FssCommActivity extends ActivityBase {

    // Extra column names for the cursor
    private static final String DISTANCE = "DISTANCE";
    private static final String BEARING = "BEARING";

    // Use a 40NM radius
    private static final int RADIUS = 40;

    private LinearLayout mMainLayout;
    private LayoutInflater mInflater;

    @Override
    protected void onCreate( Bundle savedInstanceState ) {
        super.onCreate( savedInstanceState );

        requestWindowFeature( Window.FEATURE_INDETERMINATE_PROGRESS );

        mInflater = getLayoutInflater();
        setContentView( R.layout.wait_msg );

        Intent intent = getIntent();
        String siteNumber = intent.getStringExtra( Airports.SITE_NUMBER );
        FssCommTask task = new FssCommTask();
        task.execute( siteNumber );
    }

    private final class ComData implements Comparable<ComData> {

        public String[] mColumnValues;

        public ComData( Cursor c, float declination, Location location ) {
            mColumnValues = new String[ c.getColumnCount()+2 ];
            int i = 0;
            while ( i < c.getColumnCount() ) {
                mColumnValues[ i ] = c.getString( i );
                ++i;
            }

            // Now calculate the distance to this wx station
            float[] results = new float[ 2 ];
            Location.distanceBetween( location.getLatitude(), location.getLongitude(), 
                    c.getDouble( c.getColumnIndex( Com.COMM_OUTLET_LATITUDE_DEGREES ) ),
                    c.getDouble( c.getColumnIndex( Com.COMM_OUTLET_LONGITUDE_DEGREES ) ),
                    results );
            // Bearing
            mColumnValues[ i ] = String.valueOf( ( results[ 1 ]+declination+360 )%360 );
            ++i;
            // Distance
            mColumnValues[ i ] = String.valueOf( results[ 0 ]/GeoUtils.METERS_PER_NAUTICAL_MILE );
        }

        @Override
        public int compareTo( ComData another ) {
            // Last element in the value array is the distance
            int indexOfDistance = mColumnValues.length-1;
            double distance1 = Double.valueOf( mColumnValues[ indexOfDistance ] );
            double distance2 = Double.valueOf( another.mColumnValues[ indexOfDistance ] );
            if ( distance1 > distance2 ) {
                return 1;
            } else if ( distance1 < distance2 ) {
                return -1;
            }
            return 0;
        }
        
    }
    private final class FssCommTask extends AsyncTask<String, Void, Cursor[]> {

        @Override
        protected void onPreExecute() {
            setProgressBarIndeterminateVisibility( true );
        }

        @Override
        protected Cursor[] doInBackground( String... params ) {
            String siteNumber = params[ 0 ];

            SQLiteDatabase db = mDbManager.getDatabase( DatabaseManager.DB_FADDS );
            Cursor[] result = new Cursor[ 2 ];

            Cursor apt = mDbManager.getAirportDetails( siteNumber );
            result[ 0 ] = apt;

            double lat = apt.getDouble( apt.getColumnIndex( Airports.REF_LATTITUDE_DEGREES ) );
            double lon = apt.getDouble( apt.getColumnIndex( Airports.REF_LONGITUDE_DEGREES ) );
            Location location = new Location( "" );
            location.setLatitude( lat );
            location.setLongitude( lon );

            // Get the bounding box first to do a quick query as a first cut
            double[] box = GeoUtils.getBoundingBox( location, RADIUS );

            double radLatMin = box[ 0 ];
            double radLatMax = box[ 1 ];
            double radLonMin = box[ 2 ];
            double radLonMax = box[ 3 ];

            // Check if 180th Meridian lies within the bounding Box
            boolean isCrossingMeridian180 = ( radLonMin > radLonMax );
            String selection = "("
                +Com.COMM_OUTLET_LATITUDE_DEGREES+">=? AND "+Com.COMM_OUTLET_LATITUDE_DEGREES+"<=?"
                +") AND ("+Com.COMM_OUTLET_LONGITUDE_DEGREES+">=? "
                +(isCrossingMeridian180? "OR " : "AND ")+Com.COMM_OUTLET_LONGITUDE_DEGREES+"<=?)"
                +" and "+Nav1.NAVAID_TYPE+" != 'VOT'";
            String[] selectionArgs = {
                    String.valueOf( Math.toDegrees( radLatMin ) ), 
                    String.valueOf( Math.toDegrees( radLatMax ) ),
                    String.valueOf( Math.toDegrees( radLonMin ) ),
                    String.valueOf( Math.toDegrees( radLonMax ) )
                    };

            SQLiteQueryBuilder builder = new SQLiteQueryBuilder();
            builder.setTables( Com.TABLE_NAME+" c LEFT OUTER JOIN "+Nav1.TABLE_NAME+" n"
                    +" ON c."+Com.ASSOC_NAVAID_ID+" = n."+Nav1.NAVAID_ID );
            Cursor c = builder.query( db, 
                    new String[] { "c.*", "n."+Nav1.NAVAID_NAME,
                    "n."+Nav1.NAVAID_TYPE, "n."+Nav1.NAVAID_FREQUENCY },
                    selection, selectionArgs, null, null, null, null );

            String[] columnNames = new String[ c.getColumnCount()+2 ];
            int i = 0;
            for ( String col : c.getColumnNames() ) {
                columnNames[ i++ ] = col;
            }
            columnNames[ i++ ] = BEARING;
            columnNames[ i++ ] = DISTANCE;
            MatrixCursor matrix = new MatrixCursor( columnNames );

            if ( c.moveToFirst() ) {
                // Now find the magnetic declination at this location
                float declination = GeoUtils.getMagneticDeclination( location );

                ComData[] comDataList = new ComData[ c.getCount() ];
                int row = 0;
                do {
                    ComData com = new ComData( c, declination, location );
                    comDataList[ row++ ] = com;
                } while ( c.moveToNext() );

                // Sort the FSS Com list by distance
                Arrays.sort( comDataList );

                // Build a cursor out of the sorted FSS station list
                for ( ComData com : comDataList ) {
                    float distance = Float.valueOf(
                            com.mColumnValues[ matrix.getColumnIndex( DISTANCE ) ] );
                    if ( distance <= RADIUS ) {
                        matrix.addRow( com.mColumnValues );
                    }
                }
            }

            c.close();

            result[ 1 ] = matrix;

            return result;
        }

        @Override
        protected void onPostExecute( Cursor[] result ) {
            setProgressBarIndeterminateVisibility( false );

            View view = mInflater.inflate( R.layout.fss_detail_view, null );
            setContentView( view );
            mMainLayout = (LinearLayout) view.findViewById( R.id.fss_top_layout );

            // Title
            Cursor apt = result[ 0 ];
            showAirportTitle( mMainLayout, apt );

            showFssDetails( result );
        }

    }

    private void showFssDetails( Cursor[] result ) {
        Cursor com = result[ 1 ];
        LinearLayout detailLayout = (LinearLayout) mMainLayout.findViewById(
                R.id.fss_detail_layout );
        if ( com.moveToFirst() ) {
            do {
                String outletId = com.getString( com.getColumnIndex( Com.COMM_OUTLET_ID ) );
                String outletType = com.getString( com.getColumnIndex( Com.COMM_OUTLET_TYPE ) );
                String outletCall = com.getString( com.getColumnIndex( Com.COMM_OUTLET_CALL ) );
                String navId = com.getString( com.getColumnIndex( Com.ASSOC_NAVAID_ID ) );
                String navName = com.getString( com.getColumnIndex( Nav1.NAVAID_NAME ) );
                String navType = com.getString( com.getColumnIndex( Nav1.NAVAID_TYPE ) );
                String navFreq = com.getString( com.getColumnIndex( Nav1.NAVAID_FREQUENCY ) );
                String freqs = com.getString( com.getColumnIndex( Com.COMM_OUTLET_FREQS ) );
                String fssId = com.getString( com.getColumnIndex( Com.FSS_IDENT ) );
                String fssName = com.getString( com.getColumnIndex( Com.FSS_NAME ) );
                float bearing  = com.getFloat( com.getColumnIndex( BEARING ) );
                float distance  = com.getFloat( com.getColumnIndex( DISTANCE ) );

                Log.i( "BEARING", String.valueOf( bearing ) );
                Log.i( "DISTANCE", String.valueOf( distance ) );

                LinearLayout layout = (LinearLayout) mInflater.inflate( 
                        R.layout.fss_detail_item, null );
                TextView tv = (TextView) layout.findViewById( R.id.fss_comm_name );
                if ( navId.length() > 0 ) {
                    tv.setText( navId+" - "+navName+" "+navType );
                } else {
                    tv.setText( outletId+" - "+outletCall );
                }
                TableLayout table = (TableLayout) layout.findViewById( R.id.fss_comm_details );
                addRow( table, "FSS", fssName+" ("+fssId+")" );
                int i =0;
                while ( i < freqs.length() ) {
                    addSeparator( table );
                    int end = Math.min( i+9, freqs.length() );
                    String freq = freqs.substring( i, end ).trim();
                    addRow( table, outletType, freq );
                    i += end;
                }
                if ( navId.length() > 0 ) {
                    addSeparator( table );
                    addRow( table, navId+" "+navType, navFreq );
                }
                addSeparator( table );
                if ( distance < 1.0 ) {
                    addRow( table, "Distance", "On-site" );
                } else {
                    addRow( table, "Distance", String.format( "%.0fNM %s", distance,
                        GeoUtils.getCardinalDirection( bearing ) ) );
                }

                detailLayout.addView( layout, new LinearLayout.LayoutParams(
                        LayoutParams.FILL_PARENT, LayoutParams.WRAP_CONTENT ) );
            } while ( com.moveToNext() );
        } else {
            TextView tv = new TextView( this );
            tv.setGravity( Gravity.CENTER_HORIZONTAL|Gravity.CENTER_VERTICAL );
            tv.setText( String.format( "No FSS communication outlets found within %dNM radius.",
                    RADIUS ) );
            detailLayout.addView( tv, new LinearLayout.LayoutParams(
                    LayoutParams.FILL_PARENT, LayoutParams.FILL_PARENT ) );
        }
    }

    protected void addRow( TableLayout table, String label, String value ) {
        TableRow row = (TableRow) mInflater.inflate( R.layout.airport_detail_item, null );
        TextView tv = new TextView( this );
        tv.setText( label );
        tv.setSingleLine();
        tv.setGravity( Gravity.LEFT );
        tv.setPadding( 4, 2, 2, 2 );
        row.addView( tv, new TableRow.LayoutParams(
                LayoutParams.FILL_PARENT, LayoutParams.WRAP_CONTENT, 1f ) );
        tv = new TextView( this );
        tv.setText( value );
        tv.setMarqueeRepeatLimit( -1 );
        tv.setGravity( Gravity.RIGHT );
        tv.setPadding( 2, 2, 4, 2 );
        row.addView( tv, new TableRow.LayoutParams(
                LayoutParams.FILL_PARENT, LayoutParams.WRAP_CONTENT, 0f ) );
        table.addView( row, new TableLayout.LayoutParams(
                LayoutParams.FILL_PARENT, LayoutParams.WRAP_CONTENT ) );
    }

    protected void addSeparator( LinearLayout layout ) {
        View separator = new View( this );
        separator.setBackgroundColor( Color.LTGRAY );
        layout.addView( separator, new LayoutParams( LayoutParams.FILL_PARENT, 1 ) );
    }

}