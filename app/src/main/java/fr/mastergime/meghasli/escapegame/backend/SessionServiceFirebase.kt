package fr.mastergime.meghasli.escapegame.backend

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import fr.mastergime.meghasli.escapegame.model.Enigme
import fr.mastergime.meghasli.escapegame.model.Session
import fr.mastergime.meghasli.escapegame.model.User
import fr.mastergime.meghasli.escapegame.model.UserForRecycler
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

class SessionServiceFirebase @Inject constructor() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    lateinit var user: User
    lateinit var session: Session
    var message = ""

    //to create a new Session
    suspend fun createSession(name: String): String {
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()
        val userList: MutableList<String> = mutableListOf()
        //try to create a Session
        userList.add(auth.currentUser!!.uid)
        session = Session("null", name, userList, false, false, "null")
        val state = createSessionInDatabase(session)
        addEnigmesToSection(name)
        addOptionalEnigma(name)
        return state
    }

    //used inside createSession
    private suspend fun createSessionInDatabase(session: Session): String {
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()
        val sessionIdMap = mutableMapOf<String, Any>()
        val userSessionIdMap = mutableMapOf<String, Any>()
        var createSessionState = "Unknown Error"
        try {
            db.collection("Sessions").add(session).addOnSuccessListener { docRef ->
                session.id = docRef.id
                sessionIdMap["id"] = session.id
                userSessionIdMap["sessionId"] = session.id
            }.addOnFailureListener {
                createSessionState = "FailedCreateSession"
            }.await()

            if (createSessionState == "FailedCreateSession") {
                db.collection("Sessions").document(session.id).delete()
            } else {
                db.collection("Users").document(auth.currentUser!!.uid)
                    .set(userSessionIdMap, SetOptions.merge()).addOnFailureListener {
                        createSessionState = "FailedUserStep"
                    }.await()
            }
            if (createSessionState == "FailedUserStep") {
                db.collection("Sessions").document(session.id).delete()
            } else {
                db.collection("Sessions").document(session.id).set(
                    sessionIdMap,
                    SetOptions.merge()
                ).addOnSuccessListener {
                    createSessionState = "Success"
                }.addOnFailureListener {
                    createSessionState = "FailedSessionStep"
                }.await()
            }
            if (createSessionState == "FailedSessionStep") {
                userSessionIdMap["sessionId"] = "null"
                db.collection("Users").document(auth.currentUser!!.uid)
                    .set(userSessionIdMap, SetOptions.merge()).await()
                db.collection("Sessions").document(session.id).delete().await()
            }

        } catch (e: Exception) {
            createSessionState = "Fatal Exception : $e"
        }
        return createSessionState
    }

    //join session created by another player
    suspend fun joinSession(name: String): String {
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()
        var joinSessionState = "Unknown Error"
        val userListMap = mutableMapOf<String, Any>()
        val sessionIdMap = mutableMapOf<String, Any>()

        try {
            userListMap["usersList"] = FieldValue.arrayUnion(auth.currentUser?.uid)
            val sessionQuery = db.collection("Sessions")
                .whereEqualTo("name", name).get().await()

            if (sessionQuery.documents.isNotEmpty()) {
                for (document in sessionQuery) {
                    sessionIdMap["sessionId"] = document.id

                    db.collection("Users").document(
                        auth.currentUser!!.uid
                    ).set(sessionIdMap, SetOptions.merge())
                        .addOnFailureListener {
                            joinSessionState = "FailedUserStep"
                        }.await()

                    if (joinSessionState != "FailedUserStep")
                        db.collection("Sessions")
                            .document(document.id).update(userListMap).addOnSuccessListener {
                                joinSessionState = "Success"
                            }.addOnFailureListener {
                                joinSessionState = "FailedSessionStep"
                            }.await()

                    if (joinSessionState == "FailedSessionStep") {
                        sessionIdMap["sessionId"] = "null"
                        db.collection("Users").document(
                            auth.currentUser!!.uid
                        ).set(sessionIdMap, SetOptions.merge()).await()
                    }
                }
            } else {
                joinSessionState = "UnknownSession"
            }

        } catch (e: Exception) {
            joinSessionState = "Fatal exception $e"
        }
        return joinSessionState
    }

    //quite Session
    suspend fun quitSession(): String {
        Log.d("START_DELETE", "quitSession: ")
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()
        var quitSessionState = "Unknown Error"
        val userListMap = mutableMapOf<String, Any>()
        val userSessionIdMap = mutableMapOf<String, Any>()
        userListMap["usersList"] = FieldValue.arrayRemove(auth.currentUser!!.uid)
        userSessionIdMap["sessionId"] = "null"
        lateinit var usersList: ArrayList<*>

        try {
            val userQuery = db.collection("Users")
                .whereEqualTo("id", auth.currentUser!!.uid).get().await()

            if (userQuery.documents.isNotEmpty()) {
                for (document in userQuery) {
                    val sessionId = document.getString("sessionId") as String

                    db.collection("Sessions")
                        .document(sessionId).update(userListMap).addOnFailureListener {
                            quitSessionState = "FailedSessionStep"
                        }.await()
                    if (quitSessionState != "FailedSessionStep") {

                        db.collection("Users")
                            .document(auth.currentUser!!.uid).update(userSessionIdMap)
                            .addOnSuccessListener {
                                quitSessionState = "Success"
                            }.addOnFailureListener {
                                quitSessionState = "FailedUserStep"
                            }.await()

                        val sessionQuery = db.collection("Sessions")
                            .whereEqualTo("id", sessionId).get().await()

                        if (sessionQuery.documents.isNotEmpty()) {
                            for (document2 in sessionQuery) {
                                usersList = document2.get("usersList") as ArrayList<*>
                            }

                            if (usersList.size < 1 && quitSessionState == "Success") {
                                val enigmeQuery = db.collection("Sessions").document(sessionId)
                                    .collection("enigmes").get().await()

                                if (!enigmeQuery.isEmpty) {
                                    for( document in enigmeQuery){
                                        db.collection("Sessions").document(sessionId)
                                            .collection("enigmes").document(document.id).delete().await()
                                    }
                                }

                                db.collection("Sessions").document(sessionId)
                                    .delete().await()
                            }
                        } else {
                            quitSessionState = "FailedFindSession"
                        }
                    }
                }

            } else {
                quitSessionState = "FailedFindUser"
            }
        } catch (e: Exception) {
            quitSessionState = "Fatal Exception $e"
        }
        return quitSessionState
    }

    //to get the users of the session
    suspend fun getUsersList(): MutableList<UserForRecycler> {
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        val userNameList = mutableListOf<UserForRecycler>()

        try {
            val userQuery = db.collection("Users")
                .whereEqualTo("id", auth.currentUser!!.uid).get().await()

            if (userQuery.documents.isNotEmpty()) {
                for (document in userQuery) {
                    val sessionId = document.getString("sessionId")
                    val sessionQuery = db.collection("Sessions")
                        .whereEqualTo("id", sessionId).get().await()

                    if (sessionQuery.documents.isNotEmpty()) {

                        for (document2 in sessionQuery) {
                            val usersList = document2.get("usersList") as ArrayList<*>

                            for (user in usersList) {
                                val userDocument = db.collection("Users")
                                    .document(user as String).get()
                                    .addOnSuccessListener { userDocument ->
                                        if(userDocument != null ){
                                            if(userDocument.exists()){
                                                val userName = userDocument.get("pseudo") as String
                                                val ready = userDocument.get("ready") as Boolean
                                                val userForRecycler = UserForRecycler(userName, ready)
                                                userNameList.add(userForRecycler)
                                                Log.d("Username12 :", "Operation Success !")
                                            }
                                        }
                                    }.await()
                            }
                        }
                    }
                }
                Log.d("getUsersList :", "Successful")

            }
        } catch (e: Exception) {
            Log.d("getUsersList :", "Failed $e")
        }
        return userNameList
    }

    //to launch the game inside session room
    suspend fun launchSession(): String {
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()
        val stateMap = mutableMapOf<String, Any>()
        stateMap["state"] = true
        var launchSessionState = "Unknown Error"
        if (getPlayersState()) {
            try {
                val userQuery = db.collection("Users")
                    .whereEqualTo("id", auth.currentUser!!.uid).get().await()
                if (userQuery.documents.isNotEmpty()) {
                    for (document in userQuery) {
                        val sessionId = document.get("sessionId") as String
                        db.collection("Sessions").document(sessionId)
                            .set(stateMap, SetOptions.merge()).addOnSuccessListener {
                                launchSessionState = "Success"
                                Log.d("Launch Session : ", "Succeed")
                            }.await()
                    }

                }
            } catch (e: Exception) {
                launchSessionState = "Failed"
            }
            Log.d("Launch Session : ", "OK")
        } else {
            launchSessionState = "Waiting for other Players"
        }

        return launchSessionState
    }

    //CheckTimer  && setTimer && getTimer ( to push )
    suspend fun startSessionTimer(): Long {
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()
        val timerMap = mutableMapOf<String, Any>()
        var sessionId = ""
        var milli: Long = 0
        var timerStarted = false

        // timerMap["endAt"] = FieldValue.serverTimestamp()
        timerMap["endAt"] = System.currentTimeMillis() + 615000
        val milSeconde = FieldValue.serverTimestamp()

        var timerMessageResult = "Unknown Error"
        try {
            val userQuery = db
                .collection("Users")
                .document(auth.currentUser!!.uid)

            userQuery.get()
                .addOnSuccessListener { document ->
                    if (document != null) {
                        sessionId = document.data!!["sessionId"] as String
                    } else {
                        Log.d("USER_EMPTY", "No such document")
                    }
                }.await()

            val timerQuery = db
                .collection("Sessions")
                .document(sessionId)

            if (sessionId != "null"){
                timerQuery.get()
                    .addOnSuccessListener { document ->
                        if (document != null) {
                            val timestamp: Boolean = document.data!!["timerStarted"] as Boolean
                            timerStarted = timestamp
                        } else {
                            Log.d("TIMER_EMPTY", "No such document")
                        }
                    }
                    .addOnFailureListener { exception ->
                        Log.d("TIMER_FAIL", "get failed with ", exception)
                    }.await()

                if (!timerStarted) {
                    db.collection("Sessions").document(sessionId)
                        .set(timerMap, SetOptions.merge()).addOnSuccessListener {
                            timerMessageResult = "Success"
                            timerStarted = true
                        }.await()
                    if (timerStarted) {
                        val updateTimerState = mutableMapOf<String, Any>()
                        updateTimerState["timerStarted"] = true
                        db.collection("Sessions").document(sessionId)
                            .set(updateTimerState, SetOptions.merge()).addOnSuccessListener {
                                timerMessageResult = "Success"
                            }.await()
                        timerQuery.get()
                            .addOnSuccessListener { document ->
                                if (document != null) {
                                    val timestamp: Long = document.data!!["endAt"] as Long
                                    milli = timestamp
                                } else {
                                    Log.d("TIMER_EMPTY", "No such document")
                                }
                            }
                            .addOnFailureListener { exception ->
                                Log.d("TIMER_FAIL", "get failed with ", exception)
                            }.await()
                    }
                } else {
                    timerQuery.get()
                        .addOnSuccessListener { document ->
                            if (document != null) {
                                val timestamp: Long = document.data!!["endAt"] as Long
                                milli = timestamp
                            } else {
                                Log.d("TIMER_EMPTY", "No such document")
                            }
                        }
                        .addOnFailureListener { exception ->
                            Log.d("TIMER_FAIL", "get failed with ", exception)
                        }.await()
                }
            }


        } catch (e: Exception) {
            timerMessageResult = "Fatal Exception : $e"
        }
        return milli
    }

    suspend fun setUpBonusTimer() {
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()
        var sessionId = ""
        val timerMap = mutableMapOf<String, Any>()

        try {
            val userQuery = db
                .collection("Users")
                .document(auth.currentUser!!.uid)

            userQuery.get()
                .addOnSuccessListener { document ->
                    if (document != null) {
                        sessionId = document.data!!["sessionId"] as String
                    } else {
                        Log.d("USER_EMPTY", "No such document")
                    }
                }.await()

            val timerQuery = db
                .collection("Sessions")
                .document(sessionId)

            timerQuery.get()
                .addOnSuccessListener { document ->
                    if (document != null) {
                        timerMap["endAt"] = document.data!!["endAt"] as Long + 120000
                    } else {
                        Log.d("TIMER_EMPTY", "No such document")
                    }
                }
                .addOnFailureListener { exception ->
                    Log.d("TIMER_FAIL", "get failed with ", exception)
                }.await()

            db.collection("Sessions").document(sessionId)
                .set(timerMap, SetOptions.merge()).addOnSuccessListener {

                }.addOnFailureListener {
                    Log.d("TIMER_FAIL", "get failed with ", it)
                }.await()

        } catch (e: Exception) {

        }
    }

    //to get the state of the session use this fun
    suspend fun getSessionState(): Boolean {
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()
        var sessionState = false
        try {
            val userQuery = db.collection("Users")
                .whereEqualTo("id", auth.currentUser!!.uid).get(Source.SERVER).await()
            if (userQuery.documents.isNotEmpty()) {
                for (document in userQuery) {
                    val sessionId = document.get("sessionId") as String
                    val sessionQuery = db.collection("Sessions")
                        .whereEqualTo("id", sessionId).get(Source.SERVER).await()
                    for (document2 in sessionQuery) {
                        sessionState = document2.get("state") as Boolean
                    }
                }
            }
            Log.d("get Session State :", "Successful")
        } catch (e: Exception) {
            Log.d("get Session State :", "Failed")
        }
        return sessionState
    }

    //sessionName
    suspend fun getSessionName(): String {
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()
        var sessionName = "null"
        try {
            val userQuery = db.collection("Users")
                .whereEqualTo("id", auth.currentUser!!.uid).get().await()
            if (userQuery.documents.isNotEmpty()) {
                for (document in userQuery) {
                    val sessionId = document.get("sessionId") as String
                    val sessionQuery = db.collection("Sessions")
                        .whereEqualTo("id", sessionId).get().await()
                    for (document2 in sessionQuery) {
                        sessionName = document2.get("name") as String
                    }
                }
            }
        } catch (e: Exception) {
            Log.d("getSessionName : ", "Failed $e")
        }
        return sessionName
    }

    //getIDSession
    suspend fun getSessionIdFromUser(): String {
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()
        var sessionId = "Empty"
        try {
            val userQuery = db.collection("Users")
                .whereEqualTo("id", auth.currentUser!!.uid).get(Source.SERVER).await()
            if (userQuery.documents.isNotEmpty()) {
                for (document in userQuery) {
                    sessionId = document.get("sessionId") as String
                }
            }
        } catch (e: Exception) {
            Log.d("getSessionIdFromUser : ", "Failed")
        }

        return sessionId
    }

    //to get the state id
    suspend fun getSessionId(): String {

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()
        var sessionId = ""
        try {
            val userQuery = db.collection("Users")
                .whereEqualTo("id", auth.currentUser!!.uid).get().await()
            if (userQuery.documents.isNotEmpty()) {
                for (document in userQuery) {
                    sessionId = document.get("sessionId") as String
                }
            }
            Log.d("sessionIdx", sessionId)
            Log.d("get Session state : ", "Successful")
            return sessionId
        } catch (e: Exception) {
            Log.d("get Session State :", "failed$e")
            return sessionId
        }
    }

    suspend fun addEnigmesToSection(nameSession: String) {

        db = FirebaseFirestore.getInstance()
        val sessionQuery = db.collection("Sessions")
            .whereEqualTo("name", nameSession).get().await()
        Log.d("sessionQuery", sessionQuery.toString())

        if (sessionQuery.documents.isNotEmpty()) {
            for (document in sessionQuery.documents) {

                val sessionId = document.getString("id") as String
                Log.d("sessionId", sessionId)
                for (i in 0..fillEnigmes().size - 1) {
                    db.collection("Sessions").document(sessionId).collection("enigmes")
                        .document(fillEnigmes()[i].name)
                        .set(fillEnigmes()[i])
                }
            }
        }
    }

    suspend fun addOptionalEnigma(nameSession: String) {
        db = FirebaseFirestore.getInstance()
        val sessionQuery = db.collection("Sessions")
            .whereEqualTo("name", nameSession).get().await()
        if (sessionQuery.documents.isNotEmpty()) {
            for (document in sessionQuery.documents) {
                val sessionId = document.getString("id") as String
                val enigme = hashMapOf<String, Any?>()
                enigme["id"] = 5
                enigme["name"] = "optional"
                enigme["state"] = false
                enigme["indice"] = ""
                enigme["reponse"] = ""
                enigme["closed"] = false
                enigme["playedTime"] = 0
                db.collection("Sessions").document(sessionId).collection("Optional")
                    .document("Optional")
                    .set(enigme)
            }
        }
    }

    suspend fun getPlayersState(): Boolean {
        val playersStateList = mutableListOf<Boolean>()
        var playersState = false
        try {
            val userQuery = db.collection("Users")
                .whereEqualTo("id", auth.currentUser!!.uid).get().await()

            if (userQuery.documents.isNotEmpty()) {
                for (document in userQuery) {
                    val sessionId = document.getString("sessionId")
                    val sessionQuery = db.collection("Sessions")
                        .whereEqualTo("id", sessionId).get(Source.SERVER).await()

                    if (sessionQuery.documents.isNotEmpty()) {

                        for (document2 in sessionQuery) {
                            val usersList = document2.get("usersList") as ArrayList<*>

                            for (user in usersList) {
                                val userDocument = db.collection("Users")
                                    .document(user as String).get(Source.SERVER)
                                    .addOnSuccessListener { userDocument ->
                                        val ready = userDocument.get("ready") as Boolean
                                        playersStateList.add(ready)
                                    }.await()
                            }
                        }
                    }
                }
            }

        } catch (e: Exception) {

        }
        if (playersStateList.isNotEmpty()) {
            for (state in playersStateList) {
                if (state == false) {
                    playersState = false
                    break
                } else {
                    playersState = true
                }
            }
        }
        Log.d("playerState", "$playersState")
        return playersState
    }

    suspend fun readyPlayer(): String {
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()
        val stateMap = mutableMapOf<String, Any>()
        stateMap["ready"] = true
        var playerState = "Unknown Error"
        try {
            db.collection("Users").document(auth.currentUser!!.uid)
                .set(stateMap, SetOptions.merge()).addOnSuccessListener {
                    playerState = "Success"
                }.await()

            Log.d("Launch Session : ", "Succeed")
        } catch (e: Exception) {
            playerState = "Fatal Exception : $e"
        }
        return playerState
    }

    suspend fun notReadyPlayer(): String {
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()
        val stateMap = mutableMapOf<String, Any>()
        stateMap["ready"] = false
        var playerState = "Unknown Error"
        try {
            db.collection("Users").document(auth.currentUser!!.uid)
                .set(stateMap, SetOptions.merge()).addOnSuccessListener {
                    playerState = "Success"
                }.await()

            Log.d("Launch Session : ", "Succeed")
        } catch (e: Exception) {
            playerState = "Fatal Exception : $e"
        }
        return playerState
    }

    fun fillEnigmes(): ArrayList<Enigme> {
        val enigme1 = Enigme(0, "Death Chapter", "0430", false, "DEATH = 0430",false)
        val enigme21 = Enigme(1, "Crime Chapter P1", "letus", false, "letus = 24975",false)
        val enigme22 = Enigme(2, "Crime Chapter P2", "", false, "",false)
        val enigme3 = Enigme(3, "Live Chapter", "2184", false, "Live = 2184",false)
        val enigme4 = Enigme(4, "The Last", "249752184", false, "",false)

        var enigmesArray = ArrayList<Enigme>()
        enigmesArray.add(enigme1)
        enigmesArray.add(enigme21)
        enigmesArray.add(enigme22)
        enigmesArray.add(enigme3)
        enigmesArray.add(enigme4)

        return enigmesArray
    }

    suspend fun writeNameServerBluetoothOnFirebase(deviceName: String): String {
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()
        var deviceNameState = "Unknown Error"
        val stateMap = mutableMapOf<String, Any>()
        stateMap["deviceName"] = deviceName
        try {
            val userQuery = db.collection("Users")
                .whereEqualTo("id", auth.currentUser!!.uid).get().await()
            if (userQuery.documents.isNotEmpty()) {
                for (document in userQuery) {
                    val sessionId = document.get("sessionId") as String
                    db.collection("Sessions").document(sessionId)
                        .set(stateMap, SetOptions.merge()).addOnSuccessListener {
                            deviceNameState = "Success"
                        }.await()
                }

            }
        } catch (e: Exception) {
            deviceNameState = "Fatal Exception : $e"
        }
        return deviceNameState
    }

    suspend fun readNameServerBluetoothOnFirebase(): String {
        auth    = FirebaseAuth.getInstance()
        db      = FirebaseFirestore.getInstance()
        var deviceName = ""

        try {
            val userQuery = db.collection("Users")
                .whereEqualTo("id", auth.currentUser!!.uid).get().await()
            if (userQuery.documents.isNotEmpty()) {
                for (document in userQuery) {
                    val sessionId = document.get("sessionId") as String
                    val sessionQuery = db.collection("Sessions")
                        .whereEqualTo("id", sessionId).get().await()
                    for (document2 in sessionQuery) {
                        deviceName = document2.get("deviceName") as String
                    }

                }
            }
        } catch (e: Exception) {
            deviceName = "Exception"
        }
        return deviceName
    }

}