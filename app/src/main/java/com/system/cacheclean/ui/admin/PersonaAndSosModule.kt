package com.system.cacheclean.ui.admin

// ═══════════════════════════════════════════════════════════════════════════════
// PersonaManagerModule.kt
// Contains: PersonaViewModel, PersonaManagerFragment, PersonaAdapter
// ═══════════════════════════════════════════════════════════════════════════════

import android.app.AlertDialog
import android.app.Application
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.system.cacheclean.R
import com.system.cacheclean.db.StealthGuardDatabase
import com.system.cacheclean.db.entity.PersonaEntity
import com.system.cacheclean.db.entity.SosContactEntity
import com.system.cacheclean.model.Gender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


// ─── PersonaViewModel ────────────────────────────────────────────────────────

class PersonaViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = StealthGuardDatabase.getInstance(app).personaDao()
    val allPersonas: LiveData<List<PersonaEntity>> = dao.getAllPersonas()

    // N-10: surfaces a reason when delete/disable is silently blocked,
    // instead of the user tapping "Remove" and nothing visibly happening.
    private val _actionError = MutableLiveData<String>()
    val actionError: LiveData<String> = _actionError

    fun toggleEnabled(persona: PersonaEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            // Guard: do not disable the LAST enabled persona
            val enabledCount = dao.countEnabled()
            if (persona.isEnabled && enabledCount <= 1) {
                _actionError.postValue("Cannot disable the last enabled persona")
                return@launch
            }
            dao.setEnabled(persona.id, !persona.isEnabled)
        }
    }

    fun addPersona(name: String, gender: Gender) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.insert(PersonaEntity(displayName = name, gender = gender.name))
        }
    }

    fun delete(persona: PersonaEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val enabledCount = dao.countEnabled()
            if (persona.isEnabled && enabledCount <= 1) {
                _actionError.postValue("Cannot remove the last enabled persona")
                return@launch
            }
            dao.delete(persona)
        }
    }
}


// ─── PersonaManagerFragment ───────────────────────────────────────────────────

class PersonaManagerFragment : Fragment() {

    private val viewModel: PersonaViewModel by viewModels()
    private lateinit var adapter: PersonaAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_persona_manager, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = PersonaAdapter(
            onToggle = { viewModel.toggleEnabled(it) },
            onDelete = { persona ->
                AlertDialog.Builder(requireContext())
                    .setTitle("Remove Persona")
                    .setMessage("Remove '${persona.displayName}' from the caller pool?")
                    .setPositiveButton("Remove") { _, _ -> viewModel.delete(persona) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        )

        view.findViewById<RecyclerView>(R.id.rvPersonas).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@PersonaManagerFragment.adapter
        }

        viewModel.allPersonas.observe(viewLifecycleOwner) { adapter.submitList(it) }
        viewModel.actionError.observe(viewLifecycleOwner) { msg ->
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }

        view.findViewById<FloatingActionButton>(R.id.fabAddPersona)
            .setOnClickListener { showAddPersonaDialog() }
    }

    private fun showAddPersonaDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_add_persona, null)

        val tilName    = dialogView.findViewById<TextInputLayout>(R.id.tilPersonaName)
        val etName     = dialogView.findViewById<TextInputEditText>(R.id.etPersonaName)
        val rgGender   = dialogView.findViewById<RadioGroup>(R.id.rgGender)

        AlertDialog.Builder(requireContext())
            .setTitle("Add Caller Persona")
            .setView(dialogView)
            .setPositiveButton("Add", null)
            .setNegativeButton("Cancel", null)
            .create()
            .also { dialog ->
                dialog.show()
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val name = etName.text?.toString()?.trim() ?: ""
                    if (name.isEmpty()) {
                        tilName.error = "Name cannot be empty"
                        return@setOnClickListener
                    }
                    tilName.error = null
                    val gender = if (rgGender.checkedRadioButtonId == R.id.rbGentsPersona)
                        Gender.GENTS else Gender.LADY
                    viewModel.addPersona(name, gender)
                    dialog.dismiss()
                }
            }
    }
}


// ─── PersonaAdapter ───────────────────────────────────────────────────────────

class PersonaAdapter(
    private val onToggle: (PersonaEntity) -> Unit,
    private val onDelete: (PersonaEntity) -> Unit
) : ListAdapter<PersonaEntity, PersonaAdapter.VH>(PersonaDiff()) {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName:    TextView  = view.findViewById(R.id.tvPersonaName)
        val tvGender:  TextView  = view.findViewById(R.id.tvPersonaGender)
        val swEnabled: Switch    = view.findViewById(R.id.swPersonaEnabled)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDeletePersona)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context)
            .inflate(R.layout.item_persona, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val persona = getItem(position)
        holder.tvName.text   = persona.displayName
        holder.tvGender.text = if (persona.gender == "GENTS") "♂ Gents" else "♀ Lady"
        holder.tvGender.setTextColor(
            if (persona.gender == "GENTS") 0xFF1565C0.toInt() else 0xFFAD1457.toInt()
        )

        // Avoid triggering the listener during bind
        holder.swEnabled.setOnCheckedChangeListener(null)
        holder.swEnabled.isChecked = persona.isEnabled
        holder.swEnabled.setOnCheckedChangeListener { _, _ -> onToggle(persona) }

        holder.btnDelete.setOnClickListener { onDelete(persona) }
    }

    class PersonaDiff : DiffUtil.ItemCallback<PersonaEntity>() {
        override fun areItemsTheSame(a: PersonaEntity, b: PersonaEntity) = a.id == b.id
        override fun areContentsTheSame(a: PersonaEntity, b: PersonaEntity) = a == b
    }
}


// ═══════════════════════════════════════════════════════════════════════════════
// SOS Settings Module
// Contains: SosSettingsViewModel, SosSettingsFragment, SosContactAdapter
// ═══════════════════════════════════════════════════════════════════════════════

class SosSettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = StealthGuardDatabase.getInstance(app).sosContactDao()
    val allContacts: LiveData<List<SosContactEntity>> = dao.getAllContacts()

    // N-11: surfaces a reason when the 3-contact limit silently blocks add.
    private val _actionError = MutableLiveData<String>()
    val actionError: LiveData<String> = _actionError

    fun addContact(phone: String, nickname: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (dao.count() >= 3) {
                _actionError.postValue("Limit reached — only 3 SOS contacts allowed")
                return@launch   // Hard limit of 3
            }
            dao.insert(SosContactEntity(phoneNumber = phone, nickname = nickname))
        }
    }

    fun toggleEnabled(contact: SosContactEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.setEnabled(contact.id, !contact.isEnabled)
        }
    }

    fun delete(contact: SosContactEntity) {
        viewModelScope.launch(Dispatchers.IO) { dao.delete(contact) }
    }
}


// ─── SosSettingsFragment ──────────────────────────────────────────────────────

class SosSettingsFragment : Fragment() {

    private val viewModel: SosSettingsViewModel by viewModels()
    private lateinit var adapter: SosContactAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_sos_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = SosContactAdapter(
            onToggle = { viewModel.toggleEnabled(it) },
            onDelete = { contact ->
                AlertDialog.Builder(requireContext())
                    .setTitle("Remove Contact")
                    .setMessage("Remove '${contact.nickname}' from SOS contacts?")
                    .setPositiveButton("Remove") { _, _ -> viewModel.delete(contact) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        )

        view.findViewById<RecyclerView>(R.id.rvSosContacts).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@SosSettingsFragment.adapter
        }

        viewModel.allContacts.observe(viewLifecycleOwner) { list ->
            adapter.submitList(list)
            val limitNote = view.findViewById<TextView>(R.id.tvContactLimit)
            limitNote.text = "${list.size}/3 contacts configured"
            view.findViewById<FloatingActionButton>(R.id.fabAddContact).apply {
                isEnabled = list.size < 3
                alpha = if (list.size < 3) 1f else 0.4f
            }
        }
        viewModel.actionError.observe(viewLifecycleOwner) { msg ->
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }

        view.findViewById<FloatingActionButton>(R.id.fabAddContact)
            .setOnClickListener { showAddContactDialog() }

        // Preview button — shows formatted SOS message WITHOUT sending
        view.findViewById<Button>(R.id.btnPreviewSos)
            .setOnClickListener { showMessagePreview() }
    }

    private fun showAddContactDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_add_sos_contact, null)

        val tilPhone    = dialogView.findViewById<TextInputLayout>(R.id.tilPhone)
        val etPhone     = dialogView.findViewById<TextInputEditText>(R.id.etPhone)
        val tilNickname = dialogView.findViewById<TextInputLayout>(R.id.tilNickname)
        val etNickname  = dialogView.findViewById<TextInputEditText>(R.id.etNickname)

        AlertDialog.Builder(requireContext())
            .setTitle("Add Trusted Contact")
            .setView(dialogView)
            .setPositiveButton("Add", null)
            .setNegativeButton("Cancel", null)
            .create()
            .also { dialog ->
                dialog.show()
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val phone    = etPhone.text?.toString()?.trim() ?: ""
                    val nickname = etNickname.text?.toString()?.trim() ?: ""
                    if (phone.isEmpty()) {
                        tilPhone.error = "Phone number required"; return@setOnClickListener
                    }
                    if (nickname.isEmpty()) {
                        tilNickname.error = "Nickname required"; return@setOnClickListener
                    }
                    tilPhone.error    = null
                    tilNickname.error = null
                    viewModel.addContact(phone, nickname)
                    dialog.dismiss()
                }
            }
    }

    private fun showMessagePreview() {
        val previewMsg = "EMERGENCY! I need help. Please call me immediately.\n" +
                "My last known location:\n" +
                "https://maps.google.com/?q=28.6139,77.2090\n\n" +
                "[Sent automatically]"
        AlertDialog.Builder(requireContext())
            .setTitle("SOS Message Preview")
            .setMessage(previewMsg)
            .setPositiveButton("OK", null)
            .show()
    }
}


// ─── SosContactAdapter ────────────────────────────────────────────────────────

class SosContactAdapter(
    private val onToggle: (SosContactEntity) -> Unit,
    private val onDelete: (SosContactEntity) -> Unit
) : ListAdapter<SosContactEntity, SosContactAdapter.VH>(ContactDiff()) {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvNickname: TextView   = view.findViewById(R.id.tvContactNickname)
        val tvPhone:    TextView   = view.findViewById(R.id.tvContactPhone)
        val swEnabled:  Switch     = view.findViewById(R.id.swContactEnabled)
        val btnDelete:  ImageButton = view.findViewById(R.id.btnDeleteContact)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context)
            .inflate(R.layout.item_sos_contact, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val contact = getItem(position)
        holder.tvNickname.text = contact.nickname
        holder.tvPhone.text    = contact.phoneNumber

        holder.swEnabled.setOnCheckedChangeListener(null)
        holder.swEnabled.isChecked = contact.isEnabled
        holder.swEnabled.setOnCheckedChangeListener { _, _ -> onToggle(contact) }
        holder.btnDelete.setOnClickListener { onDelete(contact) }
    }

    class ContactDiff : DiffUtil.ItemCallback<SosContactEntity>() {
        override fun areItemsTheSame(a: SosContactEntity, b: SosContactEntity) = a.id == b.id
        override fun areContentsTheSame(a: SosContactEntity, b: SosContactEntity) = a == b
    }
}
