package es.citaasistente.app.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import es.citaasistente.app.R
import es.citaasistente.app.data.Catalog
import es.citaasistente.app.data.Nif
import es.citaasistente.app.data.Request
import es.citaasistente.app.data.Status
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Lista de solicitudes de la cola (una tarjeta por solicitud, con su estado). */
class RequestAdapter(
    private val catalog: Catalog,
    private val onClick: (Request) -> Unit,
    private val onCheckChange: (Request, Boolean) -> Unit,
) : RecyclerView.Adapter<RequestAdapter.VH>() {

    private var items: List<Request> = emptyList()
    private val selectedIds = mutableSetOf<String>()
    private val fmt = SimpleDateFormat("dd/MM HH:mm", Locale("es", "ES"))

    fun submit(list: List<Request>) { 
        items = list
        selectedIds.clear()
        notifyDataSetChanged() 
    }

    fun getSelectedIds(): List<String> = selectedIds.toList()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.tvName)
        val status: TextView = v.findViewById(R.id.tvStatus)
        val where: TextView = v.findViewById(R.id.tvWhere)
        val meta: TextView = v.findViewById(R.id.tvMeta)
        val checkbox: androidx.appcompat.widget.AppCompatCheckBox = v.findViewById(R.id.cbSelect)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_request, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val r = items[position]
        val p = r.profile
        val prov = catalog.province(p.provinceCode)
        h.name.text = "${r.displayName()} · ${Nif.mask(p.docNumber)}"
        h.where.text = "${prov?.name ?: (if (p.provinceCode.isBlank()) "Cualquier provincia" else "?")} · ${prov?.procedure(p.procedureId)?.name ?: p.procedureId}"
        h.meta.text = buildString {
            append("Intentos: ${r.attempts} · desde ${p.minDate}")
            if (r.slot.isNotBlank()) append(" · cita: ${r.slot}")
            append("\nActualizada ${fmt.format(Date(r.updatedAt))}")
        }
        h.status.text = r.status.label
        val bg = GradientDrawable().apply { cornerRadius = 40f; setColor(Color.parseColor(color(r.status))) }
        h.status.background = bg
        
        // Lógica del Checkbox
        h.checkbox.setOnCheckedChangeListener(null) // Prevenir disparos reciclados
        h.checkbox.isChecked = selectedIds.contains(r.id)
        h.checkbox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) selectedIds.add(r.id) else selectedIds.remove(r.id)
            onCheckChange(r, isChecked)
        }
        
        h.itemView.setOnClickListener { onClick(r) }
    }

    private fun color(s: Status) = when (s) {
        Status.PENDIENTE -> "#B58900"
        Status.EN_PROCESO -> "#C60B1E"
        Status.ESPERANDO_HUMANO -> "#8E0715"
        Status.CON_CITA -> "#2E7D32"
        Status.FALLIDA -> "#6D4C41"
        Status.CANCELADA -> "#757575"
    }
}
