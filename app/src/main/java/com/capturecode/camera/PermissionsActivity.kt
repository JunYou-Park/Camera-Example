package com.capturecode.camera

import android.content.Intent
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import com.camera.utils.PermissionUtils
import com.camera.utils.FileUtils.TYPE_PICTURE
import com.camera.utils.FileUtils.TYPE_VIDEO
import com.capturecode.camera.ui.Camera2Activity
import com.capturecode.camera.ui.CameraXActivity

class PermissionsActivity : AppCompatActivity() {
    companion object{
        private const val TAG = "PermissionsActivity"
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if(!PermissionUtils.hasPermissions(this)){
            Log.d(TAG, "onCreate: has not permission, hasPermissions is ${PermissionUtils.hasPermissions(this)}")
            requestPermissions(PermissionUtils.PERMISSIONS_REQUIRED, PermissionUtils.PERMISSIONS_REQUEST_CODE)
            return
        }

        init()
    }

    private fun init(){
        findViewById<Button>(R.id.btn_camerax_picture).setOnClickListener {
            val intent = Intent(this, CameraXActivity::class.java)
            intent.type = TYPE_PICTURE
            startActivity(intent)
        }

        findViewById<Button>(R.id.btn_camerax_video).setOnClickListener {
            val intent = Intent(this, CameraXActivity::class.java)
            intent.type = TYPE_VIDEO
            startActivity(intent)
        }

        findViewById<Button>(R.id.btn_camera2_picture).setOnClickListener {
            val intent = Intent(this, Camera2Activity::class.java)
            intent.type = TYPE_PICTURE
            startActivity(intent)
        }

        findViewById<Button>(R.id.btn_camera2_video).setOnClickListener {
            val intent = Intent(this, Camera2Activity::class.java)
            intent.type = TYPE_VIDEO
            startActivity(intent)
        }
//        if(intent.type=="CameraX/Video"){
//        }
//        else if(intent.type=="CameraX/Picture"){
//        }
//        else if(intent.type=="Camera2/Video"){
//        }
//        else if(intent.type=="Camera2/Picture"){
//        }
//        else{
//
////            val intent = Intent(this, Camera2Activity::class.java)
//            val intent = Intent(this, CameraXActivity::class.java)
//            intent.type = TYPE_VIDEO
//            startActivity(intent)
//            finish()
//        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PermissionUtils.PERMISSIONS_REQUEST_CODE) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                init()
            } else {
                Toast.makeText(this, "Permission request denied", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }
}