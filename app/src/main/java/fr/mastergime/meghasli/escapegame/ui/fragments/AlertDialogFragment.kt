package fr.mastergime.meghasli.escapegame.ui.fragments


import android.content.DialogInterface
import android.content.res.Resources
import android.graphics.Rect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import fr.mastergime.meghasli.escapegame.databinding.AlertDialogBinding
import fr.mastergime.meghasli.escapegame.viewmodels.SessionViewModel
import kotlinx.android.synthetic.main.fragment_log.*

@AndroidEntryPoint
class AlertDialogFragment(private val clickListener:()->Unit, private val clickListener2:()->Unit) : DialogFragment() {

    lateinit var binding : AlertDialogBinding
    val sessionViewModel: SessionViewModel by viewModels()

    override fun onStart() {
        super.onStart()
        setPopupSize(80,30)

    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {

        binding = AlertDialogBinding.inflate(layoutInflater)
        return  binding.root
    }



    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.confirmButton.setOnClickListener {
            clickListener2()
        }

        binding.cancelButton.setOnClickListener {
            clickListener()

        }

    }

    fun setPopupSize(width: Int, height : Int) {
        val dm = Resources.getSystem().displayMetrics
        val rect = dm.run { Rect(0, 0, widthPixels, heightPixels) }
        val percentWidth = rect.width() * width.toFloat() / 100
        val percentHeight = rect.height() * height.toFloat() / 100
        dialog?.window?.setLayout(percentWidth.toInt(), percentHeight.toInt())
        dialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }
}
