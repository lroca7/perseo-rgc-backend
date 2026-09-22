package com.perseo.rgc.controller

import com.perseo.rgc.dto.AporteRequest
import com.perseo.rgc.dto.SocioRequest
import com.perseo.rgc.model.Aporte
import com.perseo.rgc.model.Socio
import com.perseo.rgc.repository.AporteRepository
import com.perseo.rgc.repository.SocioRepository
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/socios")
class SocioController(private val socioRepository: SocioRepository) {

    @GetMapping
    fun listar(): List<Socio> = socioRepository.findAll()

    @GetMapping("/{id}")
    fun obtener(@PathVariable id: String): ResponseEntity<Socio> =
        socioRepository.findById(id).map { ResponseEntity.ok(it) }.orElse(ResponseEntity.notFound().build())

    @PostMapping
    fun crear(@RequestBody req: SocioRequest): Socio =
        socioRepository.save(Socio(nombre = req.nombre, activo = req.activo, fechaIngreso = req.fechaIngreso))

    @PutMapping("/{id}")
    fun actualizar(@PathVariable id: String, @RequestBody req: SocioRequest): ResponseEntity<Socio> {
        if (!socioRepository.existsById(id)) return ResponseEntity.notFound().build()
        val actualizado = socioRepository.save(Socio(id = id, nombre = req.nombre, activo = req.activo, fechaIngreso = req.fechaIngreso))
        return ResponseEntity.ok(actualizado)
    }

    @DeleteMapping("/{id}")
    fun eliminar(@PathVariable id: String): ResponseEntity<Void> {
        socioRepository.deleteById(id)
        return ResponseEntity.noContent().build()
    }
}

@RestController
@RequestMapping("/api/aportes")
class AporteController(private val aporteRepository: AporteRepository) {

    @GetMapping
    fun listar(@RequestParam(required = false) socioId: String?): List<Aporte> =
        if (socioId != null) aporteRepository.findBySocioId(socioId) else aporteRepository.findAll()

    @PostMapping
    fun crear(@RequestBody req: AporteRequest): Aporte =
        aporteRepository.save(
            Aporte(socioId = req.socioId, periodo = req.periodo, fecha = req.fecha, monto = req.monto, nota = req.nota)
        )

    @DeleteMapping("/{id}")
    fun eliminar(@PathVariable id: String): ResponseEntity<Void> {
        aporteRepository.deleteById(id)
        return ResponseEntity.noContent().build()
    }
}
