## Why 

Trino DistDoc — A Trino UDAF plugin for high-throughput, layer-by-layer introspection of polymorphic JSON documents.

## TL;DR

run `mise install`, `mise run lc-demo` and inspect:
- test data in `build/dev-lakehouse/data`
- generated custom schema at `build/dev-lakehouse/schema`

## Naming

In historical medicine, a **district doctor** (or [county doctor](https://en.wikipedia.org/wiki/A_Young_Doctor%27s_Notebook)) was a versatile, lone practitioner dispatched to remote regions to manage complex health crises with limited tools. They relied heavily on acute introspection—carefully examining symptoms, identifying anomalies, and writing a precise **prescription (Rx)** for treatment before the patient could be safely integrated back into society.

This project adopts this metaphor for modern data engineering:

* **The Remote Frontier:** Trino serves as the distributed infrastructure, processing raw, unpredictable data streams from the edge.
* **The Patient:** Polymorphic JSON documents represent the raw, unstable payload—frequently suffering from "schema drift" and structural anomalies.
* **The District Doctor (DistDoc):** This UDAF plugin acts as the primary inspecting clinician. It performs deep, frictionless introspection of the data stream, diagnosing structural "symptoms" layer by layer without modifying the payload itself.
* **The Prescription (Rx Data):** Instead of forcing immediate, heavy normalization, **DistDoc** outputs a lightweight **.rx.json** file. This "medical prescription" documents the exact state of the drift and outlines the treatment plan, which is later handed over downstream to **Normamed** for precise execution and data alignment.

## Overview

Project map for humans and agents: [agents_bootstrap.md](./agents_bootstrap.md).
Detailed specs live in [docs](./docs).