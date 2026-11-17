package com.yourteam.brainchair.signal

import java.io.File
import java.io.IOException

interface TraceStorage:AutoCloseable {
    fun latest(json:String)
    fun diagnostic(line:String)
    fun csv(header:String,row:String)
    override fun close() {}
}

/** Only the trace worker invokes these methods. No open/rotation/write at construction time. */
class FileTraceStorage(private val directory:File,private val atomicLatest:(String)->Unit,
    private val limit:Long=4*1024*1024,private val rowLimit:Int=6000,
    private val rotate:(File,File)->Unit={current,previous->current.copyTo(previous,overwrite=true);current.writeText("")}) : TraceStorage {
    private val current=File(directory,"control-diagnostics.jsonl")
    private val previous=File(directory,"control-diagnostics.previous.jsonl")
    private val csvFile=File(directory,"stage2-trace.csv")
    private var initialized=false;private var rows=0
    private fun ensureDirectory(){if(!directory.isDirectory && !directory.mkdirs())throw IOException("日志目录不可写：$directory")}
    override fun latest(json:String){ensureDirectory();atomicLatest(json)}
    override fun diagnostic(line:String){
        ensureDirectory()
        if(line.toByteArray(Charsets.UTF_8).size>65536)throw IOException("诊断单条超过64KiB限制")
        if(current.length()>=limit)rotate(current,previous) // failed rotation never appends to a full file.
        current.appendText(line+"\n",Charsets.UTF_8)
    }
    override fun csv(header:String,row:String){
        ensureDirectory()
        if(!initialized){csvFile.writeText(header,Charsets.UTF_8);initialized=true}
        if(rows>=rowLimit)return
        // A failed partial append cannot produce an unbounded CSV under repeated retry.
        if(csvFile.length()+row.toByteArray(Charsets.UTF_8).size+1>limit)return
        csvFile.appendText(row+"\n",Charsets.UTF_8);rows++
    }
}
