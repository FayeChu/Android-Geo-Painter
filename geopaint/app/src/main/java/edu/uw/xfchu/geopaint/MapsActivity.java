package edu.uw.xfchu.geopaint;

import android.Manifest;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Environment;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.os.Bundle;
import android.support.v4.content.ContextCompat;
import android.support.v4.view.MenuItemCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.ShareActionProvider;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.Toast;

import com.flask.colorpicker.ColorPickerView;
import com.flask.colorpicker.OnColorSelectedListener;
import com.flask.colorpicker.builder.ColorPickerClickListener;
import com.flask.colorpicker.builder.ColorPickerDialogBuilder;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;

import org.json.JSONException;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class MapsActivity extends AppCompatActivity implements OnMapReadyCallback,
        GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener,
        LocationListener {

    private GoogleMap mMap;
    private GoogleApiClient mGoogleApiClint;
    private LocationRequest mLocationRequest;
    private ShareActionProvider mShareActionProvider;
    private Polyline mPolyline;
    private SharedPreferences sharedPref;
    List<Polyline> lineShape;

    private boolean penDown;
    private int drawingColor;
    private String fileName;

    public static final String TAG = "MapsActivity";
    private static final String DEFAULT_FILENAME = "drawing.geojson";
    private static final int CONNECTION_FAILURE_RESOLUTION_REQUEST = 9000;
    private static final int PERMISSION_REQUEST_FINE_LOCATION = 1;
    private static final int DEFAULT_DRAWING_COLOR = -1;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_maps);

        penDown = false;
        fileName = DEFAULT_FILENAME;
        lineShape = new ArrayList<>();
        sharedPref = this.getPreferences(Context.MODE_PRIVATE);

        drawingColor = sharedPref.getInt("drawingColor", DEFAULT_DRAWING_COLOR);

        // Obtain the SupportMapFragment and get notified when the map is ready to be used.
        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);
        mapFragment.setRetainInstance(true); // retain data even when rotate the screen
        mapFragment.setHasOptionsMenu(true); // show option menu at the action bar

        // Create Google API Client that tracks the location data
        mGoogleApiClint = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(LocationServices.API)
                .build();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // handle item selected
        switch (item.getItemId()) {
            case R.id.pen:
                Log.v(TAG, "Select Pen");
                togglePen(item);
                return true;
            case R.id.pick_color:
                Log.v(TAG, "Select Picker");
                setColor();
                return true;
            case R.id.save:
                Log.v(TAG, "Select Save");
                saveDrawing();
                return true;
            case R.id.share:
                Log.v(TAG, "Select Share");
                //instantiate the ShareActionProvider for share
                mShareActionProvider = (ShareActionProvider) MenuItemCompat
                        .getActionProvider(item);
                shareDrawing();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main_menu, menu);
        if (penDown) {
            menu.getItem(0).setIcon(R.drawable.ic_create_black_24px);
        }
        else {
            menu.getItem(0).setIcon(R.drawable.ic_create_white_24px);
        }
        return true;
    }

    @Override
    protected void onStart() {
        super.onStart();
        mGoogleApiClint.connect();
        readFile();
    }

    @Override
    protected void onStop() {
        if (mGoogleApiClint.isConnected()) {
            LocationServices.FusedLocationApi.removeLocationUpdates(
                    mGoogleApiClint, this);
            mGoogleApiClint.disconnect();
        }

        if (lineShape != null && lineShape.size() > 1) {
            lineShape.add(mPolyline);
            String geoJsonString = GeoJsonConverter.convertToGeoJson(lineShape);
            new SaveState().execute(geoJsonString);
        }

        super.onStop();
    }

    private class SaveState extends AsyncTask<String, Void, String> {

        @Override
        protected String doInBackground(String... strings) {
            if (isExternalStorageWritable()) {
                try {
                    File file = new File(MapsActivity.this.getExternalFilesDir(null), fileName);
                    FileOutputStream outputStream = new FileOutputStream(file);
                    outputStream.write(strings[0].getBytes());
                    Log.v(TAG, "Geo JSON String: " + strings[0]);
                    outputStream.close();
                    Log.v(TAG, "Drawing Saved");
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            return "executed";
        }

        @Override
        protected void onPostExecute(String s) {
//            super.onPostExecute(s);
        }
    }

    @Override
    public void onMapReady(GoogleMap googleMap) {
        mMap = googleMap;
    }

    @Override
    public void onConnected(@Nullable Bundle bundle) {
        Log.i(TAG, "Location services connected");

        // Create the LocationRequest object
        mLocationRequest = LocationRequest.create().create()
                .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)
                .setInterval(10 * 1000) // 10 seconds, in milliseconds
                .setFastestInterval(5 * 1000); // 5 seconds, in milliseconds

        permissionCheck();
    }

    //Runtime permission check
    private void permissionCheck() {
        int permissionCheck = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION);
        if(permissionCheck == PackageManager.PERMISSION_GRANTED){
            mMap.setMyLocationEnabled(true);
            Location currentLoc = LocationServices.FusedLocationApi.getLastLocation(mGoogleApiClint);
            if (currentLoc != null) {
                mMap.moveCamera(CameraUpdateFactory.newLatLng(new LatLng(currentLoc.getLatitude(), currentLoc.getLongitude())));
            }

            LocationServices.FusedLocationApi.requestLocationUpdates(mGoogleApiClint, mLocationRequest, this);
        }else{
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, PERMISSION_REQUEST_FINE_LOCATION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        switch (requestCode) {
            case PERMISSION_REQUEST_FINE_LOCATION: {
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    onConnected(null);
                }
            }
            default:
                super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    @Override
    public void onConnectionSuspended(int i) {
        Log.i(TAG, "Location services suspended. Please reconnect.");
    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
        if (connectionResult.hasResolution()) {
            try {
                // Start an Activity that tries to resolve the error
                connectionResult.startResolutionForResult(this, CONNECTION_FAILURE_RESOLUTION_REQUEST);
            } catch (IntentSender.SendIntentException e) {
                e.printStackTrace();
            }
        } else {
            Log.i(TAG, "Location services connection failed with code "
                    + connectionResult.getErrorCode());
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        Log.v(TAG, "Location Changed");
        handleNewLocation(location);
    }

    private void handleNewLocation(Location location) {
        double currentLatitude = location.getLatitude();
        double currentLongitude = location.getLongitude();

        LatLng latlng = new LatLng(currentLatitude, currentLongitude);
        draw(latlng);
        mMap.moveCamera(CameraUpdateFactory.newLatLng(latlng));
    }

    // Change the state of the pen
    private void togglePen(MenuItem item) {
        if (penDown) {
            // if the current pen is down, the click will make the pen up
            penDown = false;
            item.setIcon(R.drawable.ic_create_white_24px);
            Toast.makeText(this, "Stop Drawing", Toast.LENGTH_SHORT).show();
            lineShape.add(mPolyline);
            mPolyline = null; //reset the current polyline
        } else {
            penDown = true;
            item.setIcon(R.drawable.ic_create_black_24px);
            Toast.makeText(this, "Start Drawing", Toast.LENGTH_SHORT).show();

            drawPermissionCheck();
        }
    }

    private void setColor() {
        ColorPickerDialogBuilder
                .with(this)
                .setTitle("Choose color")
                .initialColor(drawingColor)
                .wheelType(ColorPickerView.WHEEL_TYPE.CIRCLE)
                .density(12)
                .setOnColorSelectedListener(new OnColorSelectedListener() {
                    @Override
                    public void onColorSelected(int selectedColor) {
                        Toast.makeText(MapsActivity.this, "Color Selected: " + Integer.toHexString(selectedColor), Toast.LENGTH_SHORT).show();
                        Log.v(TAG, "onColorSelected: " + Integer.toHexString(selectedColor));
                    }
                })
                .setPositiveButton("ok", new ColorPickerClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int selectedColor, Integer[] allColors) {
                        drawingColor = selectedColor;
                        SharedPreferences.Editor editor = sharedPref.edit();
                        editor.putInt("drawingColor", drawingColor);
                        editor.commit();
                        Log.v(TAG, "drawingColor set to: " + Integer.toHexString(selectedColor));
                        lineShape.add(mPolyline);
                        mPolyline = null; //reset the current polyline

                        drawPermissionCheck();
                    }
                })
                .setNegativeButton("cancel", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                    }
                })
                .build()
                .show();
    }

    private void drawPermissionCheck() {
        // Runtime permission check
        int permissionCheck = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION);
        if(permissionCheck == PackageManager.PERMISSION_GRANTED) {
            Location currentLoc = LocationServices.FusedLocationApi.getLastLocation(mGoogleApiClint);
            LatLng latlng = new LatLng(currentLoc.getLatitude(), currentLoc.getLongitude());
            draw(latlng);
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, PERMISSION_REQUEST_FINE_LOCATION);
        }
    }


    private void saveDrawing() {
        Log.v(TAG, "Save Drawing");

        if (penDown) {
            lineShape.add(mPolyline);
        }

        final EditText text = new EditText(this);
        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setMessage("File Name: ")
                .setTitle("Name the File")
                .setView(text)
                .setIcon(R.drawable.ic_save_black_24px)
                .setCancelable(true)
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        Toast.makeText(MapsActivity.this, "Cancel Saving", Toast.LENGTH_SHORT).show();
                    }
                })
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int i) {
                        fileName = text.getText().toString() + ".geojson";
                        Log.v(TAG, "File Name: " + fileName);
                        if(isExternalStorageWritable()){
                            try {
                                File file = new File(MapsActivity.this.getExternalFilesDir(null), fileName);
                                FileOutputStream outputStream = new FileOutputStream(file);
                                GeoJsonConverter geoJsonConverter = new GeoJsonConverter();
                                String geoJsonString = geoJsonConverter.convertToGeoJson(lineShape);
                                outputStream.write(geoJsonString.getBytes());
                                Log.v(TAG, "GeoJson String: " + geoJsonString);
                                outputStream.close();
                            } catch (IOException ioe) {
                                ioe.printStackTrace();
                            }
                            Log.v(TAG, "Drawing Saved");
                            Toast.makeText(MapsActivity.this, "Drawing Saved", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
        builder.create().show();
    }

    private void shareDrawing() {
        Log.v(TAG, "Sharing Drawing");

        Uri fileUri;

        File dir = this.getExternalFilesDir(null);
        File file = new File(dir, fileName);
        fileUri = Uri.fromFile(file);

        // share file
        Intent myShareIntent = new Intent(Intent.ACTION_SEND);
        myShareIntent.setType("text/plain");
        myShareIntent.putExtra(Intent.EXTRA_STREAM, fileUri);

        mShareActionProvider.setShareIntent(myShareIntent);

    }

    /* Checks if external storage is available for read and write */
    public boolean isExternalStorageWritable() {
        String state = Environment.getExternalStorageState();
        if (Environment.MEDIA_MOUNTED.equals(state)) {
            return true;
        }
        return false;
    }

    public void draw(LatLng latlng) {
        if (penDown) {
            // initiate polyline with the defined color
            if (mPolyline == null) {
                mPolyline = mMap.addPolyline(new PolylineOptions().color(drawingColor));
            }
            //add points to the current line
            List<LatLng> points = mPolyline.getPoints();
            points.add(latlng);
            mPolyline.setPoints(points);
            mPolyline.setColor(drawingColor);
        }
    }

    public void readFile() {
        File file = new File(MapsActivity.this.getExternalFilesDir(null), fileName);
        //Read text from file
        String geoJson = "";
        try {
            InputStream is = new FileInputStream(file);
            BufferedReader buf = new BufferedReader(new InputStreamReader(is));
            String line = buf.readLine();
            StringBuilder sb = new StringBuilder();
            while(line != null){ sb.append(line).append("\n");
                line = buf.readLine(); }
            geoJson = sb.toString();
        }
        catch (IOException e) {
            Log.e(TAG, e.toString());
        }
        try {
            if (geoJson != null) {
                List<PolylineOptions> lines = GeoJsonConverter.convertFromGeoJson(geoJson);
                Log.v(TAG, lines.toString());
                if (mMap != null) {
                    for (PolylineOptions line : lines) {
                        mPolyline = mMap.addPolyline(line);
                        lineShape.add(mPolyline);
                    }
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, e.toString());
        }
    }
}
