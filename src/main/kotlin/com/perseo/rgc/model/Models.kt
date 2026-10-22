package com.perseo.rgc.model

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.LocalDate

@Document(collection = "socios")
data class Socio(
    @Id val id: String? = null,
    var nombre: String,
    var activo: Boolean = true,
    var fechaIngreso: LocalDate = LocalDate.now(),
)

@Document(collection = "aportes")
data class Aporte(
    @Id val id: String? = null,
    @Indexed
    var socioId: String,
    /** Formato "YYYY-MM" */
    var periodo: String,
    var fecha: LocalDate,
    /** Los montos se guardan con hasta 2 decimales (centavos), igual que en el Excel original. */
    var monto: Double,
    var nota: String = "",
)

data class Cuota(
    var numero: Int,
    var fechaProgramada: LocalDate,
    var cuota: Double,
    var interes: Double,
    var capital: Double,
    var saldo: Double,
    var pagado: Boolean = false,
    var fechaPago: LocalDate? = null,
    var montoPagado: Double? = null,
    var esGracia: Boolean = false,
)

@Document(collection = "prestamos")
data class Prestamo(
    @Id val id: String? = null,
    @Indexed
    var socioId: String,
    var monto: Double,
    /** Tasa mensual como fracción, ej 0.05 = 5% */
    var tasa: Double,
    var numCuotas: Int,
    var fechaInicio: LocalDate,
    var estado: String = "activo", // activo | pagado
    var cuotas: MutableList<Cuota> = mutableListOf(),
    var nota: String = "",
)

@Document(collection = "gastos")
data class Gasto(
    @Id val id: String? = null,
    var concepto: String,
    var periodo: String,
    var fecha: LocalDate,
    var monto: Double,
)

data class DistribucionItem(
    var socioId: String,
    var aportesBase: Double,
    var monto: Double,
)

@Document(collection = "utilidades")
data class Utilidad(
    @Id val id: String? = null,
    /** Formato "YYYY-MM" */
    var periodo: String,
    var totalIntereses: Double,
    var totalGastos: Double,
    var utilidadNeta: Double,
    var distribucion: List<DistribucionItem> = emptyList(),
    var fecha: LocalDate = LocalDate.now(),
)

@Document(collection = "config")
data class ConfigGlobal(
    @Id val id: String = "global",
    var tasaDefault: Double = 0.05,
    var ultimaLiquidacion: String? = null,
    /**
     * Ajuste único de "intereses históricos no registrados": la parte de los intereses
     * que el fondo ya había cobrado antes de migrar a esta app (ej. según el Excel) y que
     * no quedó capturada como cuotas pagadas reales. Se fija una sola vez al migrar y
     * alimenta tanto "Bancos" como "Utilidad sin liquidar" — NO afecta la liquidación
     * mes a mes, que siempre se calcula solo con cuotas reales.
     */
    var ajusteInteresesHistoricos: Double = 0.0,
    /**
     * Aporte mensual que se proyecta hacia adelante para cada socio activo en la
     * simulación de capacidad de préstamos: es lo que se asume que cada socio
     * sigue pagando mes a mes.
     */
    var aporteMensualSocio: Double = 200000.0,
    /**
     * Si es true, la simulación proyecta el último aporte realmente registrado de
     * cada socio en lugar del valor global [aporteMensualSocio]. Útil porque no
     * todos los socios aportan lo mismo.
     */
    var usarUltimoAporteReal: Boolean = false,
    /**
     * Número de cuotas del préstamo nuevo que se simula en la pantalla de capacidad.
     * Define en cuántos meses ese préstamo devuelve capital e interés al fondo.
     */
    var numCuotasSimulacion: Int = 18,
    /**
     * Si es true, en la simulación de capacidad el fondo otorga en el mes de turno
     * lo que puede prestar (el menor entre el cupo del socio y la caja disponible),
     * en vez de esperar a poder prestar el cupo completo. Con esta opción todos los
     * turnos se atienden en su mes, pero cada socio recibe una fracción de su
     * capacidad y el fondo rota mucho más rápido.
     */
    var prestamosParciales: Boolean = false,
)