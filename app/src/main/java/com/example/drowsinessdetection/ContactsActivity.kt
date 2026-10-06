package com.example.drowsinessdetection

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class ContactsActivity : AppCompatActivity() {
    companion object {
        private const val SMS_PERMISSION_REQUEST = 21
    }

    private lateinit var contactsRecyclerView: RecyclerView
    private lateinit var nameEditText: EditText
    private lateinit var phoneEditText: EditText
    private lateinit var addContactButton: Button
    private lateinit var testSmsButton: Button
    private lateinit var smsStatusText: TextView
    private var smsReceiverRegistered = false
    private val smsStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val success = intent.getBooleanExtra(SmsSentReceiver.EXTRA_SUCCESS, false)
            smsStatusText.text = intent.getStringExtra(SmsSentReceiver.EXTRA_STATUS_MESSAGE)
                ?: "SMS status unavailable"
            smsStatusText.setTextColor(ContextCompat.getColor(
                this@ContactsActivity,
                if (success) R.color.success_green else R.color.error_red
            ))
        }
    }
    
    private lateinit var contacts: MutableList<Contact>
    private lateinit var adapter: ContactsAdapter
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contacts)
        
        // Initialize UI components
        contactsRecyclerView = findViewById(R.id.contactsRecyclerView)
        nameEditText = findViewById(R.id.nameEditText)
        phoneEditText = findViewById(R.id.phoneEditText)
        addContactButton = findViewById(R.id.addContactButton)
        testSmsButton = findViewById(R.id.testSmsButton)
        smsStatusText = findViewById(R.id.smsStatusText)
        
        // Load existing contacts
        contacts = Utils.getContacts(this).toMutableList()
        
        // Set up RecyclerView
        adapter = ContactsAdapter(contacts) { position ->
            removeContact(position)
        }
        contactsRecyclerView.layoutManager = LinearLayoutManager(this)
        contactsRecyclerView.adapter = adapter
        
        // Set up add contact button
        addContactButton.setOnClickListener {
            addContact()
        }
        testSmsButton.setOnClickListener { sendTestSms() }
        ContextCompat.registerReceiver(
            this,
            smsStatusReceiver,
            IntentFilter(SmsSentReceiver.ACTION_SMS_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        smsReceiverRegistered = true
    }

    private fun sendTestSms() {
        if (contacts.isEmpty()) {
            smsStatusText.text = "Add an emergency contact first."
            smsStatusText.setTextColor(ContextCompat.getColor(this, R.color.error_red))
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.SEND_SMS), SMS_PERMISSION_REQUEST)
            smsStatusText.text = "Allow SMS permission, then tap the test button again."
            return
        }
        Utils.smsReadinessProblem(this)?.let { problem ->
            smsStatusText.text = problem
            smsStatusText.setTextColor(ContextCompat.getColor(this, R.color.error_red))
            return
        }

        val message = "DrowzeFree test: SOS SMS setup is working for this contact."
        var queued = 0
        for ((index, contact) in contacts.withIndex()) {
            try {
                Utils.sendSMS(
                    applicationContext,
                    contact.phoneNumber,
                    message,
                    (System.currentTimeMillis() % 100000).toInt() * 100 + index * 10
                )
                queued++
            } catch (e: Exception) {
                smsStatusText.text = "Could not queue SMS for ${contact.name}: ${e.localizedMessage ?: "check SMS permission and SIM"}"
                smsStatusText.setTextColor(ContextCompat.getColor(this, R.color.error_red))
            }
        }
        if (queued > 0) {
            smsStatusText.text = "Test SMS queued for $queued contact(s); waiting for mobile network result…"
            smsStatusText.setTextColor(ContextCompat.getColor(this, R.color.warning_yellow))
        }
    }
    
    private fun addContact() {
        val name = nameEditText.text.toString().trim()
        val phone = phoneEditText.text.toString().trim()
        
        if (name.isEmpty() || phone.isEmpty()) {
            Toast.makeText(this, "Please fill in all fields", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (!isValidPhoneNumber(phone)) {
            Toast.makeText(this, "Please enter a valid phone number", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Add the new contact
        val newContact = Contact(name, phone)
        contacts.add(newContact)
        
        // Save contacts
        Utils.saveContacts(this, contacts)
        
        // Update UI
        adapter.notifyItemInserted(contacts.size - 1)
        
        // Clear input fields
        nameEditText.text.clear()
        phoneEditText.text.clear()
        
        Toast.makeText(this, "Contact added", Toast.LENGTH_SHORT).show()
    }
    
    private fun removeContact(position: Int) {
        if (position < 0 || position >= contacts.size) return
        
        contacts.removeAt(position)
        
        // Save updated contacts
        Utils.saveContacts(this, contacts)
        
        // Update UI
        adapter.notifyItemRemoved(position)
        adapter.notifyItemRangeChanged(position, contacts.size)
        
        Toast.makeText(this, "Contact removed", Toast.LENGTH_SHORT).show()
    }
    
    private fun isValidPhoneNumber(phone: String): Boolean {
        // Basic validation - can be enhanced
        return phone.matches(Regex("^\\+?[0-9]{10,15}$"))
    }

    override fun onDestroy() {
        if (smsReceiverRegistered) unregisterReceiver(smsStatusReceiver)
        super.onDestroy()
    }
}
