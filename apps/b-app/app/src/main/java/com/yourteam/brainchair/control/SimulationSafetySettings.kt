package com.yourteam.brainchair.control

/** Only for Unity software simulation. These are not a physical wheelchair safety profile. */
data class SimulationSafetySettings(val startupEstop:Boolean=false,val feedbackTimeoutStop:Boolean=false) {
    fun write(putBoolean:(String,Boolean)->Unit){
        putBoolean(STARTUP,startupEstop);putBoolean(FEEDBACK,feedbackTimeoutStop)
    }
    companion object {
        const val STARTUP="simulationStartupEstop"
        const val FEEDBACK="simulationFeedbackTimeoutStop"
        fun read(getBoolean:(String,Boolean)->Boolean)=SimulationSafetySettings(
            getBoolean(STARTUP,false),getBoolean(FEEDBACK,false))
    }
}
