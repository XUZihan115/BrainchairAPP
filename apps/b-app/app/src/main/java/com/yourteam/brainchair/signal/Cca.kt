package com.yourteam.brainchair.signal

import kotlin.math.*

enum class Direction(val label: String, val frequency: Double) {
    FORWARD("前进",12.00), LEFT("左转",8.57), RIGHT("右转",6.67), BACKWARD("后退",5.45), STOP("停止",0.0)
}

/** Standard centered CCA = largest singular value of Qx^T Qy (no FBCCA, no ridge).
 * Pivoted, twice-reorthogonalized MGS drops numerically dependent columns.
 * Small symmetric Jacobi eigensolver on M^T M avoids a heavyweight Android dependency.
 */
class Cca(val rate: Int, windowSeconds:Double=SignalTiming.WINDOW_SECONDS) {
    val count = (rate*windowSeconds).roundToInt().also { require(it>0 && windowSeconds in 0.25..8.0) }
    private val references=Direction.values().take(4).map { d ->
        basis(Array(6){ c -> DoubleArray(count) { n ->
            val phase=2*PI*d.frequency*(c/2+1)*n/rate
            if(c%2==0) sin(phase) else cos(phase)
        }})
    }
    fun scores(columns: Array<DoubleArray>): DoubleArray {
        require(columns.size==9 && columns.all { it.size==count && it.all(Double::isFinite) })
        val x=basis(columns)
        if(x.isEmpty()) return DoubleArray(4)
        return references.map { y ->
            val cross=Array(x.size){ i -> DoubleArray(y.size){j -> dot(x[i],y[j])} }
            val gram=Array(y.size){i -> DoubleArray(y.size){j -> cross.sumOf { it[i]*it[j] }} }
            sqrt(maxEigenvalue(gram).coerceIn(0.0,1.0))
        }.toDoubleArray()
    }
    companion object {
        private fun dot(a:DoubleArray,b:DoubleArray):Double { var s=0.0; for(i in a.indices)s+=a[i]*b[i]; return s }
        internal fun basis(columns:Array<DoubleArray>): List<DoubleArray> {
            val work=columns.map { a -> val mean=a.average(); DoubleArray(a.size){a[it]-mean} }.toMutableList()
            val maxNorm=sqrt(work.maxOfOrNull { dot(it,it) } ?: 0.0)
            if(!maxNorm.isFinite() || maxNorm==0.0) return emptyList()
            val q=mutableListOf<DoubleArray>()
            while(work.isNotEmpty()) {
                val idx=work.indices.maxBy { dot(work[it],work[it]) }
                val v=work.removeAt(idx)
                repeat(2) { for(u in q){ val p=dot(u,v); for(i in v.indices)v[i]-=p*u[i] } }
                val norm=sqrt(dot(v,v))
                if(norm<=maxNorm*1e-10) continue
                for(i in v.indices)v[i]/=norm
                q.add(v)
                for(w in work) repeat(2){val p=dot(v,w); for(i in w.indices)w[i]-=p*v[i]}
            }
            return q
        }
        private fun maxEigenvalue(a:Array<DoubleArray>):Double {
            if(a.isEmpty())return 0.0
            repeat(200) {
                var p=0; var q=0; var off=0.0
                for(i in a.indices)for(j in i+1 until a.size)if(abs(a[i][j])>off){off=abs(a[i][j]);p=i;q=j}
                if(off<1e-13)return a.indices.maxOf { a[it][it] }
                val angle=0.5*atan2(2*a[p][q],a[q][q]-a[p][p]); val c=cos(angle); val s=sin(angle)
                val pp=a[p][p]; val qq=a[q][q]; val pq=a[p][q]
                for(k in a.indices)if(k!=p && k!=q){val kp=a[k][p];val kq=a[k][q];a[k][p]=c*kp-s*kq;a[p][k]=a[k][p];a[k][q]=s*kp+c*kq;a[q][k]=a[k][q]}
                a[p][p]=c*c*pp-2*s*c*pq+s*s*qq; a[q][q]=s*s*pp+2*s*c*pq+c*c*qq; a[p][q]=0.0;a[q][p]=0.0
            }
            error("CCA eigen solver did not converge")
        }
    }
}
