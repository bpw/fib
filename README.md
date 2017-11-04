# Guide to FIB Code

Benjamin P. Wood, 2017

This repository contains the implementation of FIB as presented in the
OOPSLA 2017 paper:

"Instrumentation Bias for Dynamic Data Race Detection"  
Benjamin P. Wood, Man Cao, Michael D. Bond, Dan Grossman  
Proceedings of the ACM on Programming Languages.  Vol. 1, OOPSLA,
Article 69 (October 2017), 31 pages. https://doi.org/10.1145/3133893  
Presented at OOPSLA 2017: ACM SIGPLAN Conference on Object-Oriented
Programming Systems, Languages, and Applications, October 2017

The remainder of this README gives an overview of the FIB code.

Direct questions or feedback to [Ben Wood](https://cs.wellesley.edu/~bpw/).

## Outline

- Code Overview
- Configurations
- Build
- Run
- Epochs, Epoch Maps/Read Maps, and Vector Clocks
- Access History Metadata
- Synchronization Metadata
- GC Support
- Fib Communication

## Code Overview

This code ("DR") implements FIB and other versions of the FastTrack
algorithm in [Jikes RVM](http://www.jikesrvm.org) version 3.1.3, all
based on the
[Octet code base](https://sourceforge.net/p/jikesrvm/research-archive/43/)
released with [Bond, et al., OOPSLA 2013].  Selected parts of Octet's
infrastructure for configuration and for inserting instrumentation are
reused or extended in this code, but the Octet mechanism itself is not
(despite its similarity).

Main code for the modified Jikes RVM is in `~/fibRvmRoot/rvm/src`.
The best way to navigate the code is through Eclipse, which we have
included in the VM image.  [FIXME]()

Code for FIB and the other FastTrack implementations is centered in
the `org.jikesrvm.dr` package.  Highlights:

- class `Dr` holds top-level data race detector constants
- class `DrRuntime` holds runtime hooks that are called by
  instrumentation inserted by the compiler
- package `fasttrack` contains the read and write analysis barriers
  for all FastTrack variants except FIB
    - All FastTrack implementations extend `FastTrack`; their read/write
      barriers are called from top-level analysis barriers in `DrRuntime`.
- package `fib` contains all support for FIB
    - class `FibFastTrack` contains read/write analysis barriers
    - class `FibComm` contains cross-thread communication infrastructure
- package `instrument` contains instrumentation rules
  This package interacts with support for instrumentation
  infrastructure mainly in `org.jikesrvm.octet.{InstrDecisions,Octet}`
  and `org.jikesrvm.compilers.opt.PlainFastTrackOpt*`.
- package `metadata` defines layouts and manipulations on analysis
  metadata for per-field access histories and synchronization tracking

Additional support is spread throughout existing Jikes RVM
infrastructure and is most easily explored by searching for references
to code in the `dr` package.  (In Eclipse, highlight a name and use
Control-Shift-G to search for references.)

Key parts of the code outside the `dr` package include:
- `org.jikesrvm.scheduler.RVMThread`
- Code for GC scanning and statics in `org.jikesrvm.mm.mmtk`
- Object representation extensions, instrumentation support, and
  runtime hooks also touch `org.jikesrvm.objectmodel`,
  `org.jikesrvm.compilers`, `org.jikesrvm.runtime.*Entrypoints`.
- Minor extensions elsewhere.

Modifications to existing Jikes RVM code are generally labeled with
comments using the identifiers "DR" or "FIB".

## Configurations

We use the Octet `org.jikesrvm.config` system.  All data race detector
configurations extend `org.jikesrvm.config.dr.Base`.  Here, each
relevant configuration is listed with the FastTrack implementation it
uses (from `org.jikesrvm.dr.{fasttrack,fib}`), as well as the matching
label used for this configuration in the paper.

* `UnsyncArray` (uses `UnsyncFastTrack`, matches FTUnsync in the paper):
  FastTrack with no synchronization of analysis barriers.
* `NaiveCasArray` (uses `NaiveSpinLockFastTrack`, matches FTLock):
  FastTrack with naive spin-locking on every analysis barrier.
* `CasSimpleArray` (uses `CASFastTrack`, matches FTCasSimple):
  FastTrack with coarse-grained CAS-based synchronization of analysis barriers.
* `CasArray` (uses `CASFastTrack`, matches FTCas):
  FastTrack with fine-grained CAS-based synchronization of analysis barriers.
* `FibArray` (uses `FibFastTrack`, matches FTFib):
  FastTrack with FIB for synchronization of analysis barriers.
* `FibPreemptiveReadShareArray` (uses `FibFastTrack`, matches FTFib+Share):
  FastTrack with FIB for synchronization of analysis barriers,
  with predictive read sharing.
* `FibCasArray` (uses `FibFastTrack`,  matches FTFib+Adapt):
  FastTrack with FIB for synchronization of analysis barriers,
  falling back on the CAS algorithm for highly contended locations.
* `FibPreemptiveReadShareObjectCasStaticArray` (uses `FibFastTrack`,
  matches FTFib+Adapt(SO)+Share):
  FastTrack with FIB for synchronization of analysis barriers, with
  predictive read sharing, falling back on the CAS algorithm for
  highly contended locations.


"Array" in these configuration names refers to the style of EpochMap
("read map") implementation used in these configurations.  In this
paper, we use only "Array".

The `FibArrayStats`, `FibPreemptiveReadShareArrayStats`, and
`FibCasArrayStats` configurations enable profiling of several events.

The configuration `BaseConfig` will configure Jikes RVM without any
data race detection support.

## Build

This section assumes a 64-bit x86 Linux host.  See the full user guide
<http://www.jikesrvm.org/UserGuide/> or in `./userguide` for build
dependencies and to adjust additional Jikes RVM parameters.

To build a configuration `org.jikesrvm.config.C`:

    ant -Dhost.name=x86_64-linux -Dconfig.name=FastAdaptiveGenImmix -Dconfig.config-class=org.jikesrvm.config.C

For example:

- to build Jikes RVM with FIB but no predictive read sharing:

        ant -Dhost.name=x86_64-linux -Dconfig.name=FastAdaptiveGenImmix -Dconfig.config-class=org.jikesrvm.config.dr.FibArray

- to build Jikes RVM without any data race detection support:

        ant -Dhost.name=x86_64-linux -Dconfig.name=FastAdaptiveGenImmix -Dconfig.config-class=org.jikesrvm.config.BaseConfig


## Run

To run a configuration `org.jikesrvm.config.dr.FibArray`:

    ./dist/FastAdaptiveGenImmix_dr.FibArray_x86_64-linux/rvm [JVM args] [app args]

For example, with `avrora` from DaCapo 9.12:

    ./dist/FastAdaptiveGenImmix_dr.FibArray_x86_64-linux/rvm -X:vm:errorsFatal=true -X:vm:measureCompilation=true -X:vm:measureCompilationPhases=true -Xmx1200M -cp dacapo-9.12-bach.jar Harness -s small -c MMTkCallback -n 1 avrora


## Epochs, Epoch Maps/Read Maps, and Vector Clocks

An epoch is a pair of thread ID t and logical clock c, notated
variously as "c@t" or "Tt:c".  It is a Word where the LSB is 1, the
next 5 bits (configurable in configs descending from dr.Base) are the
thread ID, and the remaining (high) bits are the clock.  Epochs are
manipulated with methods in the Epoch class.

EpochMaps ("read maps" in the paper), represent sets of last reads to
locations.  They are distinct from Vector Clocks
(org.jikesrvm.dr.metadata.VC) which are attached to threads and locks
to track synchronization.  The basic EpochMap implementation used in
all configurations for this paper is just a VC, both represented as
simple arrays.


## Access History Metadata

Every non-final non-volatile field in application/library code and
every Java array element has its own access history, a pair of
contiguous Words.  Given an object (or null, as the zero address) and
an offset, methods in the AccessHistory class manipulate the parts of
a history.

Instance field access histories are allocated inline in the same
object as the field.  The offset of a field's access history
within the object is accessible with RVMField.getDrOffset().

Static field access histories are allocated in the Statics numeric
section.  The offset of a field's access history is accessible with
RVMField.getDrOffset().

Array element access histories are stored in a lazily allocated shadow
WordArray, referenced by a header word in the data array.  Metadata
for element i starts at the 2i word in the shadow array.

Access histories of fields (or array elements) are arranged as two
contiguous words in memory: a read word and a write word.  The read
word may hold an epoch (LSB = 1), a reference to an epoch map (LSB =
0, some non-LSB not zero), or the zero word (meaning never read or
written). The write word always holds an epoch (LSB always 1) or the
zero word (meaning never written).


## Synchronization Metadata

Synchronization is tracked by vector clocks attached to each thread,
lock, volatile field, and class:

* A reference to the vector clock for a thread is stored in
  RVMThread.drThreadVC.
* A reference to the vector clock for a scalar object's monitor is
  stored in a word in the object's header.
* A reference to the vector clock for an array object's monitor is
  stored in a word in the header of the array object's shadow array.
* A reference to the vector clock for a volatile field's monitor is
  stored in a word in the same object (for instance fields) or in the
  Statics reference section (for static fields).
* A reference to the vector clock for a class's initialization time
  is stored in RVMClass.drClassInitVC.

Note that our volatile instrumentation scheme requires post-barriers.


## GC Support

To support compact metadata for synchronization and access history, we
make the following extensions to GC.

Access Histories: 

* Access histories for static fields are stored in the Statics
  non-reference section, but they contain one maybe-reference word
  (the read word) and one non-reference word (the write word).
  Scanning uses a list of all static access histories to reach these.
* Scanning calls org.jikesrvm.dr.metadata.ObjectShadow.scan(...) on
  objects during scans.  This method checks the LSB of read words
  stored in objects (including array shadows) to determine whether
  they are references or epochs, and processes the edges accordingly.
  It also scans the header word of an object, which may point to an
  array shadow or vector clock.

Synchronization:

* Thread vector clock references are stored in normal fields of
  org.jikesrvm.runtime.RVMThread.
* Scanning calls org.jikesrvm.dr.metadata.ObjectShadow.scan(...) on on
  all objects, which scans the header word holding the vector clock
  reference for objects that have been used as locks. (It also scans
  access histories, as described above.)
* Static volatile vector clock references appear as normal references
  in the Statics reference section and are scanned without special
  support.
* Instance volatile vector clock references are added to the reference
  maps for object types, so they are scanned automatically.


## Fib Communication

Communication in the Fib protocol is implemented in the
FibRequestManager -- an FRM object is attached to each RVMThread.


